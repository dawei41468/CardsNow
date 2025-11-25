package com.example.cardsnow.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cardsnow.Card
import com.example.cardsnow.ErrorType
import com.example.cardsnow.KtorViewModel
import com.example.cardsnow.MainActivity
import com.example.cardsnow.R
import com.example.cardsnow.ui.components.ActionButton
import com.example.cardsnow.ui.components.CardHand
import com.example.cardsnow.ui.components.ErrorMessage
import com.example.cardsnow.ui.components.SuccessMessage
import kotlin.math.min

@Composable
fun GameRoomScreen(viewModel: KtorViewModel, activity: MainActivity) {
    val gameState by viewModel.gameState
    val tableScrollState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val interactionSource = remember { MutableInteractionSource() }
    var showActionsBox by remember { mutableStateOf(false) }
    var showShuffleDialog by remember { mutableStateOf(false) }
    var showDealDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var selectedPlayer by remember { mutableStateOf<String?>(null) }
    var dealCountInput by remember { mutableStateOf("") }

    LaunchedEffect(gameState.roomCode) {
        if (gameState.roomCode.isNotEmpty() && !gameState.gameStarted) {
            viewModel.refreshPlayers()
            println("Forced player refresh for deal range, players: ${gameState.players}")
        }
    }

    LaunchedEffect(gameState.table.size) {
        if (gameState.table.isNotEmpty()) tableScrollState.animateScrollToItem(gameState.table.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3F51B5), Color(0xFF9FA8DA), Color(0xFF3F51B5))))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (gameState.gameStarted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconButton(onClick = { viewModel.toggleMenu() }) {
                            Icon(Icons.Default.MoreVert, "Menu", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = gameState.showMenu,
                            onDismissRequest = { viewModel.toggleMenu() }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Leave Room") },
                                onClick = { viewModel.leaveRoom() }
                            )
                            DropdownMenuItem(
                                text = { Text("Restart") },
                                onClick = { viewModel.restartGame() }
                            )
                            DropdownMenuItem(
                                text = { Text("Exit Game") },
                                onClick = { viewModel.exitGame(activity) }
                            )
                        }
                    }
                    Text(
                        "Room: ${gameState.roomCode}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }

                LazyColumn(modifier = Modifier.weight(0.25f)) {
                    items(gameState.players.filter { it != gameState.playerName }) { otherPlayer ->
                        val handSize = gameState.otherPlayersHandSizes[otherPlayer] ?: 0
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                                    .height(if (handSize == 0) 32.dp else 88.dp)
                            ) {
                                Text(
                                    "$otherPlayer's Hand ($handSize)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.Black
                                )
                                if (handSize > 0) {
                                    CardHand(
                                        cards = List(handSize) { Card("", "", R.drawable.card_back_red, "") },
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        cardWidth = 50.dp,
                                        overlapOffset = 12.dp
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.75f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Table",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, start = 4.dp, end = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(120.dp)
                                    .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                                    .padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 6.dp)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        enabled = !gameState.deckEmpty
                                    ) { viewModel.drawCard() },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Cards left: ${gameState.deckSize}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    CardHand(
                                        cards = listOf(Card("", "", if (gameState.deckEmpty) R.drawable.empty_deck else R.drawable.card_back_red, "")),
                                        cardWidth = 70.dp,
                                        overlapOffset = 0.dp
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(120.dp)
                                    .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                                    .padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 6.dp)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        enabled = gameState.discardPile.isNotEmpty()
                                    ) { viewModel.drawFromDiscard() },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Discard Pile",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (gameState.discardPile.isNotEmpty()) {
                                        CardHand(
                                            cards = gameState.discardPile,
                                            cardWidth = 70.dp,
                                            overlapOffset = 0.dp
                                        )
                                    }
                                }
                            }
                        }
                        LazyColumn(
                            state = tableScrollState,
                            modifier = Modifier.padding(top = 48.dp).padding(end = 8.dp).padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy((-34).dp)
                        ) {
                            items(gameState.table) { pile ->
                                CardHand(
                                    cards = pile,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp),
                                    cardWidth = 70.dp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp)) {
                        Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                            Text(
                                "Your Hand (${gameState.myHand.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.Black
                            )
                            Spacer(Modifier.height(8.dp))
                            CardHand(
                                cards = gameState.myHand,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                isSelectable = true,
                                selectedCards = gameState.selectedCards,
                                onCardSelected = viewModel::toggleCardSelection,
                                cardWidth = 75.dp,
                                overlapOffset = 20.dp,
                                onCardsReordered = viewModel::reorderHand
                            )
                        }
                        Text(
                            text = "Selected: ${gameState.selectedCards.count { it.value }}",
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Color(0xFFF5F5F5), MaterialTheme.shapes.small)
                                .padding(top = 8.dp, end = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    var showSortDialog by remember { mutableStateOf(false) }

                    ActionButton(
                        onClick = { showSortDialog = true },
                        text = "Sort",
                        enabled = gameState.myHand.isNotEmpty()
                    )
                    ActionButton(
                        onClick = { viewModel.playCards() },
                        text = "Play",
                        enabled = gameState.selectedCards.isNotEmpty()
                    )
                    ElevatedButton(
                        onClick = { showActionsBox = !showActionsBox },
                        modifier = Modifier,
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(0xFFFF5722),
                            contentColor = Color.White
                        ),
                        enabled = true,
                        elevation = ButtonDefaults.elevatedButtonElevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 2.dp,
                            disabledElevation = 0.dp
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Open Actions",
                            tint = Color.White
                        )
                    }

                    if (showSortDialog) {
                        AlertDialog(
                            onDismissRequest = { showSortDialog = false },
                            modifier = Modifier
                                .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                                .widthIn(max = 280.dp),
                            title = {
                                Text(
                                    text = "Sort Hand",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            text = {
                                Text(
                                    text = "Choose a sorting method:",
                                    color = Color.Black,
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    TextButton(onClick = { viewModel.sortByRank(); showSortDialog = false }) {
                                        Text("Rank", color = Color(0xFF1976D2), fontSize = 14.sp)
                                    }
                                    TextButton(onClick = { viewModel.sortBySuit(); showSortDialog = false }) {
                                        Text("Suit", color = Color(0xFF1976D2), fontSize = 14.sp)
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSortDialog = false }) {
                                    Text("Cancel", color = Color(0xFF1976D2), fontSize = 14.sp)
                                }
                            },
                            containerColor = Color(0xFFF5F5F5),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 0.dp
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFFFAFAFA), Color(0xFFE0E0E0)))),
                        elevation = CardDefaults.cardElevation(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Waiting for Players",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = min(28f, screenWidthDp / 20f).sp
                                ),
                                color = Color(0xFF3F51B5),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Room Code: ${gameState.roomCode}",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFFFF5722),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Players:",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF3F51B5),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                gameState.players.forEach {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.Black,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            if (gameState.isHost) {
                                ActionButton(
                                    onClick = { viewModel.startGame() },
                                    text = "Start Game",
                                    enabled = gameState.players.isNotEmpty(),
                                    modifier = Modifier
                                        .fillMaxWidth(1f)
                                        .height(40.dp)
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!gameState.isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Reconnecting…",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showActionsBox) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { showActionsBox = false }
            ) {
                val scale by animateFloatAsState(
                    targetValue = if (showActionsBox) 1f else 0.8f,
                    animationSpec = tween(durationMillis = 300)
                )
                val alpha by animateFloatAsState(
                    targetValue = if (showActionsBox) 1f else 0f,
                    animationSpec = tween(durationMillis = 300)
                )
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(top = 80.dp, end = 16.dp, bottom = 60.dp)
                        .widthIn(max = 280.dp)
                        .heightIn(min = 300.dp)
                        .scale(scale)
                        .alpha(alpha),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    val items = if (gameState.isHost) {
                        listOf("Shuffle", "Deal", "Discard", "Move", "Recall")
                    } else {
                        listOf("Discard", "Move", "Recall")
                    }
                    Column(
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(12.dp)
                    ) {
                        for (item in items) {
                            when (item) {
                                "Shuffle" -> {
                                    var isClicked by remember { mutableStateOf(false) }
                                    val scale by animateFloatAsState(
                                        targetValue = if (isClicked) 1.1f else 1.0f,
                                        animationSpec = tween(durationMillis = 200),
                                        finishedListener = { isClicked = false }
                                    )
                                    ElevatedButton(
                                        onClick = {
                                            if (gameState.table.isNotEmpty()) {
                                                isClicked = true
                                                showShuffleDialog = true
                                            }
                                        },
                                        modifier = Modifier
                                            .scale(scale)
                                            .width(110.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = if (gameState.table.isNotEmpty()) Color(0xFFFF5722) else Color(0xFFFFCCBC),
                                            contentColor = Color.White
                                        ),
                                        enabled = gameState.table.isNotEmpty(),
                                        elevation = ButtonDefaults.elevatedButtonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            disabledElevation = 0.dp
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Shuffle", fontSize = 16.sp, maxLines = 1)
                                    }
                                }
                                "Deal" -> {
                                    ElevatedButton(
                                        onClick = { showDealDialog = true },
                                        modifier = Modifier.width(110.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = if (!gameState.deckEmpty) Color(0xFFFF5722) else Color(0xFFFFCCBC),
                                            contentColor = Color.White
                                        ),
                                        enabled = !gameState.deckEmpty,
                                        elevation = ButtonDefaults.elevatedButtonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            disabledElevation = 0.dp
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Deal", fontSize = 16.sp, maxLines = 1)
                                    }
                                }
                                "Discard" -> {
                                    ElevatedButton(
                                        onClick = {
                                            viewModel.discardCards()
                                            showActionsBox = false
                                        },
                                        modifier = Modifier.width(110.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = if (gameState.selectedCards.isNotEmpty()) Color(0xFFFF5722) else Color(0xFFFFCCBC),
                                            contentColor = Color.White
                                        ),
                                        enabled = gameState.selectedCards.isNotEmpty(),
                                        elevation = ButtonDefaults.elevatedButtonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            disabledElevation = 0.dp
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Discard", fontSize = 16.sp, maxLines = 1)
                                    }
                                }
                                "Move" -> {
                                    ElevatedButton(
                                        onClick = { showMoveDialog = true },
                                        modifier = Modifier.width(110.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = if (gameState.selectedCards.isNotEmpty()) Color(0xFFFF5722) else Color(0xFFFFCCBC),
                                            contentColor = Color.White
                                        ),
                                        enabled = gameState.selectedCards.isNotEmpty(),
                                        elevation = ButtonDefaults.elevatedButtonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            disabledElevation = 0.dp
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Move", fontSize = 16.sp, maxLines = 1)
                                    }
                                }
                                "Recall" -> {
                                    ElevatedButton(
                                        onClick = {
                                            if (gameState.canRecall) {
                                                viewModel.recallLastPile()
                                                showActionsBox = false
                                            }
                                        },
                                        modifier = Modifier.width(110.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.elevatedButtonColors(
                                            containerColor = if (gameState.canRecall) Color(0xFFFF5722) else Color(0xFFFFCCBC),
                                            contentColor = Color.White
                                        ),
                                        enabled = gameState.canRecall,
                                        elevation = ButtonDefaults.elevatedButtonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            disabledElevation = 0.dp
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Recall", fontSize = 16.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showShuffleDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showShuffleDialog = false
                        showActionsBox = false
                    },
                    modifier = Modifier
                        .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                        .widthIn(max = 280.dp),
                    title = {
                        Text(
                            text = "Shuffle Table",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Text(
                            text = "Shuffle all table cards into the deck?",
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.shuffleDeck()
                                showShuffleDialog = false
                                showActionsBox = false
                            }
                        ) { Text("Yes", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showShuffleDialog = false
                                showActionsBox = false
                            }
                        ) { Text("No", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    containerColor = Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 0.dp
                )
            }

            if (showDealDialog) {
                val playerCount = gameState.players.size
                val maxCardsPerPlayer = gameState.deckSize / playerCount
                AlertDialog(
                    onDismissRequest = {
                        showDealDialog = false
                        showActionsBox = false
                    },
                    modifier = Modifier
                        .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                        .widthIn(max = 280.dp),
                    title = {
                        Text(
                            text = "Deal Cards",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "How many cards to deal to each player?",
                                color = Color.Black,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = dealCountInput,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    val intValue = filtered.toIntOrNull() ?: 0
                                    if (filtered.isEmpty() || intValue in 0..maxCardsPerPlayer) {
                                        dealCountInput = filtered
                                    }
                                },
                                label = { Text("Count (0 - $maxCardsPerPlayer)", fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val count = dealCountInput.toIntOrNull() ?: 0
                                if (count > 0) {
                                    viewModel.dealDeck(count)
                                    showDealDialog = false
                                    showActionsBox = false
                                    dealCountInput = ""
                                } else {
                                    viewModel.showError("Enter a number greater than 0!", ErrorType.TRANSIENT)
                                }
                            }
                        ) { Text("Deal", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showDealDialog = false
                                showActionsBox = false
                            }
                        ) { Text("Cancel", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    containerColor = Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 0.dp
                )
            }

            if (showMoveDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showMoveDialog = false
                        showActionsBox = false
                        selectedPlayer = null
                    },
                    modifier = Modifier
                        .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                        .widthIn(max = 280.dp),
                    title = {
                        Text(
                            text = "Move Cards",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "Select a player to move cards to:",
                                color = Color.Black,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            val otherPlayers = gameState.players.filter { it != gameState.playerName }
                            Column {
                                otherPlayers.forEach { player ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedPlayer = player }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = selectedPlayer == player,
                                            onClick = { selectedPlayer = player },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(player, fontSize = 14.sp, color = Color.Black)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                selectedPlayer?.let { player ->
                                    viewModel.moveCardsToPlayer(player)
                                    showMoveDialog = false
                                    showActionsBox = false
                                    selectedPlayer = null
                                } ?: viewModel.showError("Select a player!", ErrorType.TRANSIENT)
                            },
                            enabled = selectedPlayer != null
                        ) { Text("Move", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showMoveDialog = false
                                showActionsBox = false
                                selectedPlayer = null
                            }
                        ) { Text("Cancel", color = Color(0xFF1976D2), fontSize = 14.sp) }
                    },
                    containerColor = Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 0.dp
                )
            }

            if (gameState.showNewHostDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearNewHostDialog() },
                    modifier = Modifier
                        .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                        .widthIn(max = 280.dp),
                    title = {
                        Text(
                            text = "You Are Now Host",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Text(
                            text = "The previous host has disconnected. You are now the host of this game.",
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearNewHostDialog() }) {
                            Text("OK", color = Color(0xFF1976D2), fontSize = 14.sp)
                        }
                    },
                    containerColor = Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 0.dp
                )
            }

            if (gameState.isLoadingGeneral) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
        ErrorMessage(
            message = viewModel.gameState.value.errorMessage,
            errorType = viewModel.gameState.value.errorType,
            onDismiss = { viewModel.clearError() }
        )
        SuccessMessage(
            message = viewModel.gameState.value.successMessage,
            onDismiss = { viewModel.clearSuccess() }
        )
    }
}