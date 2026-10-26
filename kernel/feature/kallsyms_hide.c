#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/kernel.h>
#include <linux/seq_file.h>
#include <linux/string.h>
#include <linux/types.h>

#include "feature/kallsyms_hide.h"
#include "hook/patch_memory.h"
#include "infra/symbol_resolver.h"
#include "policy/allowlist.h"
#include "klog.h" // IWYU pragma: keep

static int (*kallsyms_show_orig)(struct seq_file *m, void *v);
static int (*modules_show_orig)(struct seq_file *m, void *v);

// 与内核 kallsyms.c 的 SUSFS 隐藏清单对齐
static const char *const ksu_hidden_prefixes[] = {
    "ksu_",            "__ksu_",           "ksud",
    "kernelsu",        "is_ksu_",          "is_manager_",
    "escape_to_",      "setup_selinux",    "track_throne",
    "on_post_fs_data", "try_umount",       "apply_kernelsu",
    "handle_sepolicy", "setup_ksu",        "__initcall__kmod_kernelsu",
};

static bool ksu_sym_hidden(const char *name)
{
    size_t i, plen;

    if (!name || !name[0])
        return false;

    // 符号名自带 ksu 标记的都要遮（如 anon_ksu_fops、is_task_ksu_domain）
    if (strstr(name, "ksu") || strstr(name, "kernelsu"))
        return true;

    for (i = 0; i < ARRAY_SIZE(ksu_hidden_prefixes); i++) {
        plen = strlen(ksu_hidden_prefixes[i]);
        if (!strncmp(name, ksu_hidden_prefixes[i], plen))
            return true;
    }
    return false;
}

// 与 kernel/kallsyms.c 中的 struct kallsym_iter 逐字段对齐（内核 6.6 arm64）
struct ksu_kallsym_iter {
    loff_t pos;
    loff_t pos_mod_end;
    loff_t pos_ftrace_mod_end;
    loff_t pos_bpf_end;
    unsigned long value;
    unsigned int nameoff;
    char type;
    char name[];
};

// struct module: enum state(4) + padding(4) + list_head(16) + name[]
#define KSU_MODULE_NAME_OFFSET 24

static int ksu_kallsyms_show(struct seq_file *m, void *p)
{
    struct ksu_kallsym_iter *iter = p;

    if (iter && iter->name[0] && ksu_sym_hidden(iter->name) &&
        !ksu_is_allow_uid_for_current(current_uid().val))
        return 0;

    return kallsyms_show_orig(m, p);
}

static bool ksu_buf_contains(const char *buf, size_t len, const char *needle)
{
    size_t nlen = strlen(needle);
    size_t i;

    if (nlen == 0 || len < nlen)
        return false;
    for (i = 0; i + nlen <= len; i++) {
        if (!memcmp(buf + i, needle, nlen))
            return true;
    }
    return false;
}

static int ksu_modules_show(struct seq_file *m, void *p)
{
    size_t before = m->count;
    int ret;

    ret = modules_show_orig(m, p);
    if (ret || m->count == before)
        return ret;

    if (ksu_is_allow_uid_for_current(current_uid().val))
        return ret;

    // 新增内容即为本模块的一行，含模块名关键字时整行丢弃
    if (ksu_buf_contains(m->buf + before, m->count - before, "kernelsu") ||
        ksu_buf_contains(m->buf + before, m->count - before, "ksu"))
        m->count = before;

    return ret;
}

static unsigned long kallsyms_ops_addr;
static void *kallsyms_show_saved;
static unsigned long modules_ops_addr;
static void *modules_show_saved;

static int ksu_patch_show(unsigned long ops_addr, void *replacement, void **saved)
{
    struct seq_operations *ops = (struct seq_operations *)ops_addr;
    void *slot;

    if (!ops->show)
        return -EINVAL;

    *saved = (void *)ops->show;
    slot = (void *)&ops->show;
    return ksu_patch_text(slot, &replacement, sizeof(void *),
                          KSU_PATCH_TEXT_FLUSH_DCACHE | KSU_PATCH_TEXT_FLUSH_ICACHE);
}

int ksu_kallsyms_hide_init(void)
{
    void *replacement;
    int ret;

    kallsyms_ops_addr = find_kernel_symbol_exact("kallsyms_op");
    if (kallsyms_ops_addr) {
        replacement = (void *)ksu_kallsyms_show;
        ret = ksu_patch_show(kallsyms_ops_addr, replacement, &kallsyms_show_saved);
        if (ret) {
            pr_err("kallsyms_hide: patch kallsyms failed: %d\n", ret);
            kallsyms_ops_addr = 0;
        } else {
            kallsyms_show_orig = (int (*)(struct seq_file *, void *))kallsyms_show_saved;
            pr_info("kallsyms_hide: kallsyms show=%px\n", kallsyms_show_saved);
        }
    } else {
        pr_err("kallsyms_hide: kallsyms_op not found\n");
    }

    modules_ops_addr = find_kernel_symbol_exact("modules_op");
    if (modules_ops_addr) {
        replacement = (void *)ksu_modules_show;
        ret = ksu_patch_show(modules_ops_addr, replacement, &modules_show_saved);
        if (ret) {
            pr_err("kallsyms_hide: patch modules failed: %d\n", ret);
            modules_ops_addr = 0;
        } else {
            modules_show_orig = (int (*)(struct seq_file *, void *))modules_show_saved;
            pr_info("kallsyms_hide: modules show=%px\n", modules_show_saved);
        }
    } else {
        pr_err("kallsyms_hide: modules_op not found\n");
    }

    return 0;
}

void ksu_kallsyms_hide_exit(void)
{
    struct seq_operations *ops;
    void *slot;

    if (kallsyms_ops_addr) {
        ops = (struct seq_operations *)kallsyms_ops_addr;
        slot = (void *)&ops->show;
        ksu_patch_text(slot, &kallsyms_show_saved, sizeof(void *),
                       KSU_PATCH_TEXT_FLUSH_DCACHE | KSU_PATCH_TEXT_FLUSH_ICACHE);
        kallsyms_ops_addr = 0;
    }

    if (modules_ops_addr) {
        ops = (struct seq_operations *)modules_ops_addr;
        slot = (void *)&ops->show;
        ksu_patch_text(slot, &modules_show_saved, sizeof(void *),
                       KSU_PATCH_TEXT_FLUSH_DCACHE | KSU_PATCH_TEXT_FLUSH_ICACHE);
        modules_ops_addr = 0;
    }

    pr_info("kallsyms_hide: uninstalled\n");
}
