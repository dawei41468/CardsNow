package com.example.cardsnow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cardsnow.KtorViewModel
import com.example.cardsnow.ui.components.ActionButton
import com.example.cardsnow.ui.components.ErrorMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomJoinScreen(viewModel: KtorViewModel) {
    val gameState = viewModel.gameState
    var roomCode by remember { mutableStateOf("") }
    var playerName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join a Room", style = MaterialTheme.typography.headlineSmall, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF3F51B5))
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF3F51B5), Color(0xFF9FA8DA), Color(0xFF3F51B5))))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (gameState.value.isLoadingGeneral) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "Join Settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF3F51B5),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = roomCode,
                                onValueChange = { roomCode = it },
                                label = { Text("Room Code") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF5722),
                                    unfocusedBorderColor = Color(0xFF3F51B5)
                                )
                            )
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = playerName,
                                onValueChange = { playerName = it },
                                label = { Text("Player Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF5722),
                                    unfocusedBorderColor = Color(0xFF3F51B5)
                                )
                            )
                            Spacer(Modifier.height(24.dp))
                            ActionButton(
                                onClick = { viewModel.joinRoom(roomCode, playerName) },
                                text = "Join",
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
            ErrorMessage(
                message = gameState.value.errorMessage,
                errorType = gameState.value.errorType,
                onDismiss = { viewModel.clearError() }
            )
        }
    }
}