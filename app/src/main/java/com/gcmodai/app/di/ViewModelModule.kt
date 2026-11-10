package com.gcmodai.app.di

import com.gcmodai.app.ui.pages.assistant.AssistantVM
import com.gcmodai.app.ui.pages.assistant.detail.AssistantDetailVM
import com.gcmodai.app.ui.pages.backup.BackupVM
import com.gcmodai.app.ui.pages.chat.ChatDrawerVM
import com.gcmodai.app.ui.pages.chat.ChatVM
import com.gcmodai.app.ui.pages.debug.DebugVM
import com.gcmodai.app.ui.pages.extensions.PromptVM
import com.gcmodai.app.ui.pages.extensions.QuickMessagesVM
import com.gcmodai.app.ui.pages.extensions.skills.SkillDetailVM
import com.gcmodai.app.ui.pages.extensions.skills.SkillsVM
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceDetailVM
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceSshEditorVM
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceVM
import com.gcmodai.app.ui.pages.favorite.FavoriteVM
import com.gcmodai.app.ui.pages.history.HistoryVM
import com.gcmodai.app.ui.pages.imggen.ImgGenVM
import com.gcmodai.app.ui.pages.mediacreation.MediaCreationSessionsVM
import com.gcmodai.app.ui.pages.mediacreation.MediaCreationVM
import com.gcmodai.app.ui.pages.memory.MemoryFileVM
import com.gcmodai.app.ui.pages.search.SearchVM
import com.gcmodai.app.ui.pages.setting.SettingVM
import com.gcmodai.app.ui.pages.share.handler.ShareHandlerVM
import com.gcmodai.app.ui.pages.stats.StatsVM
import com.gcmodai.app.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            chatService = get(),
            updateChecker = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            memoryConsolidationScheduler = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<MemoryFileVM> {
        MemoryFileVM(
            memoryId = it[0],
            path = it[1],
            memoryRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::MediaCreationSessionsVM)
    viewModel<MediaCreationVM> {
        MediaCreationVM(
            id = it.get(),
            context = get(),
            settingsStore = get(),
            repository = get(),
            service = get(),
            remoteFileStore = get(),
            appScope = get(),
        )
    }
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModel<WorkspaceSshEditorVM> {
        WorkspaceSshEditorVM(
            id = it.getOrNull(),
            repository = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
