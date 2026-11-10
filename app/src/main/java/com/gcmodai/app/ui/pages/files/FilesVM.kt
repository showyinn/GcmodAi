package com.gcmodai.app.ui.pages.files

import androidx.lifecycle.ViewModel
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.files.FilesFetch
import com.gcmodai.app.data.files.FilesQuota
import com.gcmodai.app.data.files.FilesRepository
import com.gcmodai.app.data.files.HostedFile
import com.gcmodai.app.data.files.UploadResult
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesUiState(
    val loading: Boolean = true,
    val uploading: Boolean = false,
    val quota: FilesQuota? = null,
    val files: List<HostedFile> = emptyList(),
    val error: String? = null,
    /** 失败的具体技术原因，展开可见，便于排查而不是只看到一句笼统提示 */
    val errorDetail: String? = null,
    val toast: String? = null,
    val lastUploaded: HostedFile? = null,
) {
    val isEmpty: Boolean get() = !loading && files.isEmpty()
}

class FilesVM(
    private val repository: FilesRepository,
    private val userRepository: UserRepository,
    private val scope: AppScope,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null, errorDetail = null) }
            when (val r = repository.fetch()) {
                is FilesFetch.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        quota = r.quota,
                        files = r.files,
                        error = null,
                        errorDetail = null,
                    )
                }

                is FilesFetch.Error -> _state.update {
                    it.copy(
                        loading = false,
                        error = r.message,
                        errorDetail = r.detail,
                    )
                }
            }
        }
    }

    /**
     * 上传文件。
     *
     * @param entry 部署目录名。同一目录下的文件共享一个访问前缀，
     *              这样 index.html 引用的 css/js 相对路径才能正确解析。
     */
    fun upload(name: String, bytes: ByteArray, entry: String = "") {
        if (_state.value.uploading) return
        scope.launch {
            _state.update { it.copy(uploading = true, error = null, errorDetail = null) }
            when (val r = repository.upload(name, bytes, entry)) {
                is UploadResult.Success -> {
                    _state.update {
                        it.copy(
                            uploading = false,
                            lastUploaded = r.file,
                            toast = "已上传：${r.file.name}",
                            files = listOf(r.file) + it.files.filterNot { f -> f.id == r.file.id },
                        )
                    }
                    // 上传后配额变了，静默刷新一次（失败不影响已显示的结果）
                    refresh()
                }

                is UploadResult.Rejected -> _state.update {
                    it.copy(uploading = false, error = r.message)
                }

                is UploadResult.Failed -> _state.update {
                    it.copy(uploading = false, error = r.message)
                }
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            val ok = repository.delete(id)
            if (ok) {
                _state.update { st ->
                    st.copy(
                        files = st.files.filterNot { it.id == id },
                        lastUploaded = st.lastUploaded?.takeIf { it.id != id },
                        toast = "已删除",
                    )
                }
                refresh()
            } else {
                _state.update { it.copy(error = "删除失败，请稍后重试") }
            }
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearLastUploaded() {
        _state.update { it.copy(lastUploaded = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }
}
