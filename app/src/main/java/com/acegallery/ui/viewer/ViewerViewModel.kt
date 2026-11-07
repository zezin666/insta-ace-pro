package com.acegallery.ui.viewer

import androidx.lifecycle.ViewModel
import com.acegallery.session.CameraGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(
    val session: CameraGateway,
) : ViewModel()
