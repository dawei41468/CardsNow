package com.example.cardsnow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cardsnow.ErrorType
import com.example.cardsnow.KtorViewModel
import com.example.cardsnow.ui.components.ActionButton
import com.example.cardsnow.ui.components.ErrorMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomCreationScreen(viewModel: KtorViewModel) {
    val gameState = viewModel.gameState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create a Room", style = MaterialTheme.typography.headlineSmall, color = Color.White) },
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
                                "Game Settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF3F51B5),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = gameState.value.hostName,
                                onValueChange = { viewModel.setHostName(it) },
                                label = { Text("Host Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF5722),
                                    unfocusedBorderColor = Color(0xFF3F51B5)
                                )
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Number of Decks",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF3F51B5),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = gameState.value.numDecks == 1,
                                            onClick = { viewModel.setNumDecks(1) },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF5722))
                                        )
                                        Text("1", color = Color.Black)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = gameState.value.numDecks == 2,
                                            onClick = { viewModel.setNumDecks(2) },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF5722))
                                        )
                                        Text("2", color = Color.Black)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Include Jokers",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF3F51B5),
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = gameState.value.includeJokers,
                                    onCheckedChange = { viewModel.setIncludeJokers(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF5722))
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            ActionButton(
                                onClick = {
                                    if (gameState.value.hostName.trim().isBlank()) {
                                        viewModel.showError("Please enter a host name!", ErrorType.TRANSIENT)
                                    } else {
                                        viewModel.createRoom()
                                    }
                                },
                                text = "Create",
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