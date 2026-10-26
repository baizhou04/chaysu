package com.chayu.ksu.data.repository

import com.chayu.ksu.data.model.Module
import com.chayu.ksu.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
