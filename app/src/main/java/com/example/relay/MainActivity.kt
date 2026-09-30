package com.example.relay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.relay.gateway.enrollment.EnrollmentController
import com.example.relay.ui.RelayApp
import com.example.relay.ui.RelayViewModel
import com.example.relay.ui.enrollment.EnrollmentViewModel
import com.example.relay.ui.rescue.RescueViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RelayApplication
        setContent {
            val relayViewModel: RelayViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RelayViewModel(
                        app.messageRepository,
                        app.deviceId,
                        app.deviceRoleStore,
                        app.communicationRuntime,
                        app.gatewaySettingsStore,
                        app.gatewayCredentialStore,
                        app.gatewaySyncEngine,
                        locationProvider = app.locationProvider,
                        reportSigner = app.reportSigningKeyStore,
                    ) as T
            })
            val rescueViewModel: RescueViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RescueViewModel(
                        coordinator = app.activeRescueSessionCoordinator,
                        repository = app.rescueRepository,
                        senderDeviceId = app.deviceId,
                    ) as T
            })
            val enrollmentViewModel: EnrollmentViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EnrollmentViewModel(
                        controller = EnrollmentController(app.gatewayEnrollmentStore),
                    ) as T
            })
            RelayApp(relayViewModel, rescueViewModel, enrollmentViewModel)
        }
    }
}
