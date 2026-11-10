package com.gcmodai.app.di

import android.content.Context
import com.gcmodai.app.data.files.FileFolders
import com.gcmodai.app.data.files.FilesManager
import com.gcmodai.app.data.repository.ConversationRepository
import com.gcmodai.app.data.repository.FavoriteRepository
import com.gcmodai.app.data.repository.FilesRepository
import com.gcmodai.app.data.repository.FolderRepository
import com.gcmodai.app.data.repository.GenMediaRepository
import com.gcmodai.app.data.repository.MediaCreationRepository
import com.gcmodai.app.data.repository.MemoryRepository
import com.gcmodai.app.data.repository.TranslationHistoryRepository
import com.gcmodai.app.data.repository.WorkspaceRepository
import com.gcmodai.app.data.skills.SkillManager
import com.gcmodai.workspace.ProotShellRunner
import com.gcmodai.workspace.RootfsInstaller
import com.gcmodai.workspace.WorkspaceBindMount
import com.gcmodai.workspace.WorkspaceManager
import com.gcmodai.workspace.ssh.SshConnectionPool
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表同时用于 PRoot 的 -b 参数 (命令执行与终端)、文件工具的路径解析和写入审批, 避免多处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                    writable = true,
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        SshConnectionPool()
    } onClose { it?.close() }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
