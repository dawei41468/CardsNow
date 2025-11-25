package com.example.cardsnow.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.cardsnow.Card
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CardHand(
    cards: List<Card>,
    modifier: Modifier = Modifier,
    isSelectable: Boolean = false,
    selectedCards: Map<String, Boolean> = emptyMap(),
    onCardSelected: (Card) -> Unit = {},
    cardWidth: Dp = 70.dp,
    overlapOffset: Dp = 15.dp,
    onCardsReordered: (List<Card>) -> Unit = {}
) {
    val totalWidth = if (cards.isNotEmpty()) (overlapOffset * (cards.size - 1) + cardWidth) else 280.dp
    val cardHeight = if (isSelectable) 120.dp else cardWidth * 1.5f
    var draggedCardIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var targetDropIndex by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current.density
    val hapticFeedback = LocalHapticFeedback.current
    
    // Use derivedStateOf for better performance
    val cardListState = remember(cards) { cards.toMutableStateList() }
    
    // Add drag threshold to prevent accidental drags
    val dragThreshold = with(LocalDensity.current) { 8.dp.toPx() }
    val dragStarted = remember { mutableStateOf(false) }
    val initialDragPosition = remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier.width(totalWidth)) {
        cardListState.forEachIndexed { index, card ->
            val isSelected = selectedCards[card.id] ?: false
            val isDragging = draggedCardIndex == index
            val isDropTarget = index == targetDropIndex && !isDragging
            
            // Calculate shift offset for other cards during drag
            val shiftOffset = if (!isDragging && draggedCardIndex != -1 && dragStarted.value) {
                val dragCenter = (draggedCardIndex * overlapOffset.value) + dragOffsetX + (cardWidth.value / 2)
                val thisCenter = index * overlapOffset.value + (cardWidth.value / 2)
                when {
                    dragCenter > thisCenter && index > draggedCardIndex -> overlapOffset
                    dragCenter < thisCenter && index < draggedCardIndex -> -overlapOffset
                    else -> 0.dp
                }
            } else {
                0.dp
            }

            // Animate card positions
            val animatedOffsetX by animateFloatAsState(
                targetValue = if (isDragging) dragOffsetX else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "cardOffset"
            )
            
            val animatedScale by animateFloatAsState(
                targetValue = when {
                    isDragging -> 1.1f
                    isDropTarget -> 1.05f
                    else -> 1f
                },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "cardScale"
            )
            
            val animatedZIndex by animateFloatAsState(
                targetValue = when {
                    isDragging -> 10f
                    isDropTarget -> 5f
                    else -> 0f
                },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "cardZIndex"
            )

            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .offset(
                        x = overlapOffset * index + 
                            (if (isDragging) animatedOffsetX.dp else 0.dp) + 
                            shiftOffset
                    )
                    .zIndex(animatedZIndex)
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        this.shadowElevation = if (isDragging) 16f else if (isDropTarget) 8f else 0f
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                initialDragPosition.value = offset
                                dragStarted.value = false
                                draggedCardIndex = index
                                dragOffsetX = 0f
                                targetDropIndex = index
                                println("Started dragging card at index $index: ${card.rank} of ${card.suit}")
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                
                                // Check drag threshold
                                if (!dragStarted.value) {
                                    val totalDragDistance = abs(change.position.x - initialDragPosition.value.x)
                                    if (totalDragDistance > dragThreshold) {
                                        dragStarted.value = true
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                }
                                
                                if (dragStarted.value) {
                                    dragOffsetX += dragAmount.x / density

                                    // Calculate drop index with better precision
                                    val dragPosition = (draggedCardIndex * overlapOffset.value) + dragOffsetX
                                    val calculatedDropIndex = ((dragPosition + (overlapOffset.value / 2)) / overlapOffset.value)
                                        .roundToInt()
                                        .coerceIn(0, cardListState.size - 1)

                                    if (calculatedDropIndex != targetDropIndex) {
                                        targetDropIndex = calculatedDropIndex
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            },
                            onDragEnd = {
                                if (draggedCardIndex != -1 && dragStarted.value && targetDropIndex != draggedCardIndex) {
                                    // Perform reorder with proper index adjustment
                                    val newList = cardListState.toMutableList()
                                    val draggedCard = newList.removeAt(draggedCardIndex)
                                    val adjustedDropIndex = if (targetDropIndex > draggedCardIndex) targetDropIndex - 1 else targetDropIndex
                                        .coerceIn(0, newList.size)
                                    newList.add(adjustedDropIndex, draggedCard)

                                    // Update state and notify callback
                                    cardListState.clear()
                                    cardListState.addAll(newList)
                                    onCardsReordered(newList)

                                    println("Reordered: ${newList.map { it.rank + " of " + it.suit }}")
                                }

                                // Reset drag state
                                draggedCardIndex = -1
                                dragOffsetX = 0f
                                targetDropIndex = -1
                                dragStarted.value = false
                                println("Drag ended")
                            },
                            onDragCancel = {
                                // Reset drag state on cancel
                                draggedCardIndex = -1
                                dragOffsetX = 0f
                                targetDropIndex = -1
                                dragStarted.value = false
                                println("Drag cancelled for card at index $index")
                            }
                        )
                    }
                    .then(
                        if (isSelectable && draggedCardIndex == -1) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onCardSelected(card) } else Modifier
                    )
            ) {
                Image(
                    painter = painterResource(card.resourceId),
                    contentDescription = "${card.rank} of ${card.suit}",
                    modifier = Modifier
                        .width(cardWidth)
                        .align(if (isSelectable && (isSelected || isDragging)) Alignment.TopCenter else Alignment.BottomCenter)
                )
            }
        }
    }
}