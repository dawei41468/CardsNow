package com.example.cardsnow

import androidx.compose.runtime.Composable
import com.example.cardsnow.ui.screens.GameRoomScreen
import com.example.cardsnow.ui.screens.LobbyScreen
import com.example.cardsnow.ui.screens.RoomCreationScreen
import com.example.cardsnow.ui.screens.RoomJoinScreen
import com.example.cardsnow.ui.screens.SplashScreen

@Composable
fun CardGameApp(viewModel: KtorViewModel, activity: MainActivity) {
    val gameState = viewModel.gameState
    when (gameState.value.screen) {
        "splash" -> SplashScreen { viewModel.navigateToHome() }
        "home" -> LobbyScreen(viewModel::navigateToCreate, viewModel::navigateToJoin)
        "create" -> RoomCreationScreen(viewModel)
        "join" -> RoomJoinScreen(viewModel)
        "room" -> GameRoomScreen(viewModel, activity)
    }
}