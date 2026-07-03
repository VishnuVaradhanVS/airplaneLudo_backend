package com.example


import com.example.backend.ActiveRoom
import com.example.backend.LudoSession
import com.example.shared.data.Dice
import com.example.shared.data.Game
import com.example.shared.data.GameAction
import com.example.shared.data.GamePackets
import com.example.shared.data.GridPathCords
import com.example.shared.data.LobbyAction
import com.example.shared.data.Player
import com.example.shared.data.PlayerGameState
import com.example.shared.data.Room
import com.example.shared.data.Team
import com.example.shared.data.Token
import com.example.shared.data.bluePath
import com.example.shared.data.redPath
import com.example.shared.data.safe
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 10000
    val host: String = "0.0.0.0"
    startLudoServer(port, host)
}

private var serverEngine: NettyApplicationEngine? = null
private val gameRooms = ConcurrentHashMap<Int, ActiveRoom>()

fun startLudoServer(port: Int = 8080, host: String) {
    if (serverEngine != null) return
    serverEngine = embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)
        routing {
            webSocket("/ludo/{roomId}") {
                val incomingRoomId = call.parameters["roomId"]?.toIntOrNull()
                if (incomingRoomId == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid Room ID"))
                    return@webSocket
                }
                val activeRoomCheck = gameRooms[incomingRoomId]
                if (activeRoomCheck != null && activeRoomCheck.roomData.inGame) {
                    val gamePacket = GamePackets(lobbyAction = LobbyAction.RoomInGame)
                    val jsonState = Json.encodeToString(gamePacket)
                    try {
                        send(Frame.Text(jsonState))
                        close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "Game already in progress"
                            )
                        )
                    } catch (e: Exception) {

                    }
                    return@webSocket
                }
                val playerId = System.currentTimeMillis() % 10000
                val playerName = call.request.queryParameters["name"] ?: "Player_$playerId"
                val newPlayer = Player(
                    id = playerId,
                    name = playerName,
                    team = Team.RED,
                    moveTokens = false,
                    turnsLeft = 0,
                    playerGameState = PlayerGameState.Waiting
                )
                val currentSession = LudoSession(player = newPlayer, session = this)
                val activeRoom = gameRooms.computeIfAbsent(incomingRoomId) { id ->
                    ActiveRoom(
                        roomData = Room(
                            id = id,
                            hostId = playerId,
                            baseTokenCount = 6,
                            inGame = false
                        )
                    )
                }
                newPlayer.team = if (activeRoom.sessions.size % 2 == 0) Team.RED else Team.BLUE
                activeRoom.sessions.add(currentSession)
                activeRoom.roomData =
                    activeRoom.roomData.copy(players = activeRoom.sessions.map { it.player })
                val gamePacket =
                    GamePackets(lobbyAction = LobbyAction.PlayerAck, player = newPlayer)
                val playerAckResponse = Json.encodeToString(gamePacket)
                send(Frame.Text(playerAckResponse))
                broadcastRoomState(incomingRoomId)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val jsonState = frame.readText()
                            val gamePacket = Json.decodeFromString<GamePackets>(jsonState)
                            println(gamePacket.toString())
                            when (gamePacket.lobbyAction) {
                                LobbyAction.SwapTeam -> {
                                    val player = gamePacket.player
                                    swapTeam(player, incomingRoomId)
                                }

                                LobbyAction.Kick -> {
                                    val player = gamePacket.player
                                    kickPlayer(player, incomingRoomId)
                                }

                                LobbyAction.ChangeTokenCount -> {
                                    val tokenCount = gamePacket.room?.baseTokenCount
                                    changeTokenCount(tokenCount, incomingRoomId)
                                }

                                else -> {

                                }
                            }
                            when (gamePacket.gameAction) {
                                GameAction.StartGame -> {
                                    startGame(incomingRoomId)
                                }

                                GameAction.RollDice -> {
                                    rollDice(incomingRoomId)
                                }

                                GameAction.SpawnToken -> {
                                    val selectedValue = gamePacket.selectedValue
                                    spawnToken(incomingRoomId, selectedValue)
                                }

                                GameAction.MoveToken -> {
                                    val steps = gamePacket.selectedValue
                                    val tokenId = gamePacket.moveTokenId
                                    moveToken(incomingRoomId, tokenId, steps)
                                }

                                GameAction.AnimateTokenMovement -> {
                                    val tokenId = gamePacket.moveTokenId
                                    val steps = gamePacket.selectedValue
                                    broadcastTokenAnimation(
                                        incomingRoomId, tokenId,
                                        activeRoom.roomData.game?.currentPlayer?.team!!, steps
                                    )
                                }

                                else -> {

                                }
                            }
                        }
                    }
                } catch (e: Exception) {

                } finally {
                    val activeRoom = gameRooms[incomingRoomId]
                    val leavingPlayer =
                        activeRoom?.roomData?.players?.first { p -> p.id == playerId }
                    leaveGame(incomingRoomId, leavingPlayer!!)
                    activeRoom.sessions.removeIf { it.player.id == playerId }
                    if (activeRoom.sessions.isEmpty()) {
                        gameRooms.remove(incomingRoomId)
                    } else {
                        activeRoom.roomData = activeRoom.roomData.copy(
                            players = activeRoom.sessions.map { it.player }
                        )
                        if (leavingPlayer.id == activeRoom.roomData.hostId) {
                            val nextHost = activeRoom.roomData.players.first()
                            activeRoom.roomData = activeRoom.roomData.copy(
                                hostId = nextHost.id
                            )
                        }
                        if (leavingPlayer.id == activeRoom.roomData.game?.currentPlayer?.id) {
                            val nextPlayer = getNextPlayer(incomingRoomId,leavingPlayer)
                            activeRoom.roomData = activeRoom.roomData.copy(
                                game = activeRoom.roomData.game!!.copy(
                                    currentPlayer = nextPlayer
                                )
                            )
                        }
                        broadcastRoomState(incomingRoomId)
                    }
                }
            }
        }
    }.apply { start(wait = true) }
}

suspend fun broadcastRoomState(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    val gamePacket = GamePackets(lobbyAction = LobbyAction.RoomUpdate, room = activeRoom.roomData)
    val jsonState = Json.encodeToString(gamePacket)
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {

        }
    }
}

suspend fun broadcastTokenAnimation(roomId: Int, tokenId: Int?, team: Team, steps: Int?) {
    val activeRoom = gameRooms[roomId] ?: return
    val gamePacket = GamePackets(
        gameAction = GameAction.AnimateTokenMovement,
        room = activeRoom.roomData,
        selectedValue = steps,
        moveTokenId = tokenId
    )
    val jsonState = Json.encodeToString(gamePacket)
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {
        }
    }
}

suspend fun broadcastSpawnTokenSound(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    val gamePacket = GamePackets(gameAction = GameAction.SpawnToken)
    val jsonState = Json.encodeToString(gamePacket)
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {
        }
    }
}

suspend fun closeRoom(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.close(
                CloseReason(
                    CloseReason.Codes.GOING_AWAY,
                    "Room closed by host"
                )
            )
        } catch (e: Exception) {
        }
    }
    gameRooms.remove(roomId)
}

suspend fun leaveGame(roomId: Int, player: Player) {
    val activeRoom = gameRooms[roomId] ?: return
    val gamePacket = GamePackets(gameAction = GameAction.LeaveGame, player = player)
    val jsonState = Json.encodeToString(gamePacket)
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {

        }
    }
}

suspend fun swapTeam(player: Player?, roomId: Int?) {
    if (player == null || roomId == null) {
        return
    }
    val activeRoom = gameRooms[roomId] ?: return
    val matchingSession = activeRoom.sessions.firstOrNull { it.player.id == player.id }
    if (matchingSession != null) {
        matchingSession.player.swapTeam()
        activeRoom.roomData = activeRoom.roomData.copy(
            players = activeRoom.sessions.map { it.player }
        )
        println("Server updated: Player ${matchingSession.player.name} swapped to team ${matchingSession.player.team} in room $roomId")
        broadcastRoomState(roomId)
    } else {
        println("Failed to swap team: Player ID ${player.id} not found in Room $roomId")
    }
}

suspend fun kickPlayer(player: Player?, roomId: Int?) {
    if (player == null || roomId == null) return
    val activeRoom = gameRooms[roomId] ?: return
    val targetSession = activeRoom.sessions.firstOrNull { it.player.id == player.id }

    if (targetSession != null) {
        println("Server: Forcefully removing player ${targetSession.player.name} from room $roomId")
        activeRoom.sessions.remove(targetSession)
        val updatedPlayerList = activeRoom.sessions
            .map { it.player }
            .filter { it.id != player.id }
        activeRoom.roomData = activeRoom.roomData.copy(players = updatedPlayerList)
        try {
            targetSession.session.close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "You have been kicked by the host.")
            )
        } catch (e: Exception) {
        }
        broadcastRoomState(roomId)
    }
}

suspend fun changeTokenCount(tokenCount: Int?, roomId: Int?) {
    if (roomId == null || tokenCount == null) {
        return
    }
    val activeRoom = gameRooms[roomId] ?: return
    activeRoom.roomData = activeRoom.roomData.copy(baseTokenCount = tokenCount)
    broadcastRoomState(roomId)
}

suspend fun startGame(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    val game = Game(
        getNextPlayer(roomId),
        getTokensSetup(Team.RED, activeRoom.roomData.baseTokenCount),
        getTokensSetup(Team.BLUE, activeRoom.roomData.baseTokenCount),
        false,
        false,
        activeRoom.roomData.baseTokenCount,
        activeRoom.roomData.baseTokenCount,
        0,
        0,
        Dice(0, 0),
        mutableMapOf<Int, Int>(),
        activeRoom.roomData.players.first { player -> player.team == Team.RED },
        activeRoom.roomData.players.last { player -> player.team == Team.BLUE }
    )
    activeRoom.roomData = activeRoom.roomData.copy(game = game)
    activeRoom.roomData = activeRoom.roomData.copy(inGame = true)
    broadcastRoomState(roomId)
    val gamePacket = GamePackets(gameAction = GameAction.StartGame)
    val jsonState = Json.encodeToString(gamePacket)

    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {
        }
    }

}

fun getNextPlayer(roomId: Int, currentPlayer: Player? = null): Player? {
    val activeRoom = gameRooms[roomId] ?: return null
    val players = activeRoom.roomData.players
    if (players.isEmpty()) return null
    var nextPlayer: Player? = null
    val gameData = activeRoom.roomData.game
    if (currentPlayer == null) {
        nextPlayer = players.firstOrNull { player -> player.team == Team.RED } ?: players.first()
    } else if (currentPlayer.team == Team.RED) {
        val bluePlayers = players.filter { player -> player.team == Team.BLUE }
        if (bluePlayers.isNotEmpty()) {
            val prevBlue = gameData?.prevBluePlayer
            if (prevBlue == null) {
                nextPlayer = bluePlayers.first()
            } else {
                var index = 0
                while (index < bluePlayers.size) {
                    if (bluePlayers[index].id == prevBlue.id) {
                        val nextIndex = (index + 1) % bluePlayers.size
                        nextPlayer = bluePlayers[nextIndex]
                        break
                    }
                    index++
                }
            }
        }
        if (nextPlayer == null) nextPlayer = bluePlayers.firstOrNull() ?: players.first()
    } else {
        val redPlayers = players.filter { player -> player.team == Team.RED }
        if (redPlayers.isNotEmpty()) {
            val prevRed = gameData?.prevRedPlayer
            if (prevRed == null) {
                nextPlayer = redPlayers.first()
            } else {
                var index = 0
                while (index < redPlayers.size) {
                    if (redPlayers[index].id == prevRed.id) {
                        val nextIndex = (index + 1) % redPlayers.size
                        nextPlayer = redPlayers[nextIndex]
                        break
                    }
                    index++
                }
            }
        }
        if (nextPlayer == null) nextPlayer = redPlayers.firstOrNull() ?: players.first()
    }
    val targetNextPlayer =
        nextPlayer?.copy(playerGameState = PlayerGameState.Rolling, turnsLeft = 1) ?: return null
    val newPlayers = activeRoom.roomData.players
    for (player in newPlayers) {
        if (player.id == targetNextPlayer.id) {
            player.switchPlayerState(PlayerGameState.Rolling)
            player.turnsLeft += 1
        }
        if (player.id == currentPlayer?.id) {
            player.switchPlayerState(PlayerGameState.Waiting)
            player.turnsLeft = 0
        }
    }
    activeRoom.roomData = activeRoom.roomData.copy(
        players = newPlayers
    )
    activeRoom.roomData = activeRoom.roomData.copy(
        game = activeRoom.roomData.game?.copy(
            currentPlayer = targetNextPlayer,
            diceDenomination = mutableMapOf<Int, Int>(),
            prevRedPlayer = if (targetNextPlayer.team == Team.RED) targetNextPlayer else activeRoom.roomData.game!!.prevRedPlayer,
            prevBluePlayer = if (targetNextPlayer.team == Team.BLUE) targetNextPlayer else activeRoom.roomData.game!!.prevBluePlayer
        )
    )
    return targetNextPlayer
}

fun canMoveToken(roomId: Int, diceDenomination: MutableMap<Int, Int>, team: Team): Boolean {
    val activeRoom = gameRooms[roomId]
    if (activeRoom == null) return false
    var tokens = emptyList<Token>()
    var homeEnabled = false
    if (team == Team.RED) {
        tokens = activeRoom?.roomData?.game?.redTokens ?: emptyList()
        homeEnabled = activeRoom.roomData.game?.redHomeEnabled!!
    } else {
        tokens = activeRoom?.roomData?.game?.blueTokens ?: emptyList()
        homeEnabled = activeRoom.roomData.game?.blueHomeEnabled!!
    }
    var availableSteps = mutableListOf<Int>()
    var tokensAtBase =
        if (team == Team.RED) activeRoom.roomData.game?.redBaseCount!! else activeRoom.roomData.game?.blueBaseCount!!
    for (token in tokens) {
        if (token.position == -1) {
            if (activeRoom.roomData.game!!.currentPlayer?.playerGameState == PlayerGameState.Moving) {
                if (tokensAtBase != 0 && (diceDenomination.containsKey(1) || diceDenomination.containsKey(
                        5
                    ))
                ) {
                    tokensAtBase -= 1
                    if (homeEnabled) availableSteps.add(redPath.size - 1) else availableSteps.add(
                        999999999
                    )
                }
            }
            if (activeRoom.roomData.game!!.currentPlayer?.playerGameState == PlayerGameState.Rolling) {
                if (tokensAtBase != 0) {
                    tokensAtBase -= 1
                    if (homeEnabled) availableSteps.add(redPath.size - 1) else availableSteps.add(
                        999999999
                    )
                }
            }
        } else {
            if (homeEnabled) availableSteps.add(redPath.size - 1 - token.position) else availableSteps.add(
                999999999
            )
        }
    }
    val sortedMap = diceDenomination.toSortedMap(reverseOrder())
    if (availableSteps.isEmpty() || sortedMap.isEmpty()) return false
    for ((k, v) in sortedMap) {
        for (i in 0..<v) {
            var flag = false
            for (j in 0..<availableSteps.size) {
                if (availableSteps[j] >= k) {
                    availableSteps[j] -= k
                    flag = true
                    break
                }
            }
            if (!flag) return false
        }

    }
    return true
}

fun countLastCellToken(roomId: Int): Int {
    val activeRoom = gameRooms[roomId] ?: return -1
    var currentPlayer = activeRoom.roomData.game?.currentPlayer
    var lastCellTokenCount = 0
    if (currentPlayer?.team == Team.RED) {
        for (token in activeRoom.roomData.game!!.redTokens) {
            if (token.position != -1 && token.cords.x == 3 && token.cords.y == 6) {
                lastCellTokenCount++
            }
        }
        if (activeRoom.roomData.game?.redHomeCount?.plus(lastCellTokenCount) == activeRoom.roomData.game?.redTokens?.size) {
            return lastCellTokenCount
        } else return -1
    } else {
        for (token in activeRoom.roomData.game!!.blueTokens) {
            if (token.position != -1 && token.cords.x == 3 && token.cords.y == 6) {
                lastCellTokenCount++
            }
        }
        if (activeRoom.roomData.game?.blueHomeCount?.plus(lastCellTokenCount) == activeRoom.roomData.game?.blueTokens?.size) {
            return lastCellTokenCount
        } else return -1
    }
}

suspend fun rollDice(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    val dice = Dice(0, 0)
    dice.getRandomValues()
    var currentPlayer = activeRoom.roomData.game?.currentPlayer
    val matchingSession = activeRoom.sessions.firstOrNull { it.player.id == currentPlayer?.id }
    if (matchingSession != null) {
        if (dice.sum == 1 && currentPlayer?.moveTokens == false) {
            matchingSession.player.enableMoveTokens()
            currentPlayer?.enableMoveTokens()
        }
        if (dice.doubles.contains(dice.sum)) {
            matchingSession.player.handleTurn(true)
            currentPlayer?.handleTurn(true)
        } else {
            matchingSession.player.handleTurn(false)
            currentPlayer?.handleTurn(false)
        }
        if (currentPlayer?.turnsLeft == 0) {
            matchingSession.player.switchPlayerState(PlayerGameState.Moving)
            currentPlayer?.switchPlayerState(PlayerGameState.Moving)
        }
        val lastCellTokenCount = countLastCellToken(roomId)
        if (lastCellTokenCount != -1) {
            if (dice.sum == 1) {
                if (activeRoom.roomData.game?.diceDenomination!!.getOrDefault(
                        1,
                        0
                    ) + 1 == lastCellTokenCount
                ) {
                    matchingSession.player.switchPlayerState(PlayerGameState.Moving)
                    currentPlayer?.switchPlayerState(PlayerGameState.Moving)
                }
            }
        }
        activeRoom.roomData = activeRoom.roomData.copy(
            players = activeRoom.sessions.map { it.player }
        )
    }
    var newGame = activeRoom.roomData.game?.copy()
    var diceDenomination: MutableMap<Int, Int>? = newGame?.diceDenomination
    diceDenomination?.put(dice.sum, diceDenomination?.getOrDefault(dice.sum, 0)?.plus(1) ?: 0)
    newGame = newGame?.copy(diceValue = dice, diceDenomination = diceDenomination!!)
    activeRoom.roomData = activeRoom.roomData.copy(game = newGame)
    broadcastRoomState(roomId)
    if (!currentPlayer?.moveTokens!! && currentPlayer.turnsLeft == 0) {
        delay(800)
        getNextPlayer(roomId, currentPlayer)
        activeRoom.roomData.game?.diceDenomination?.clear()
        broadcastRoomState(roomId)
        return
    }
    if (!canMoveToken(roomId, diceDenomination!!, currentPlayer.team)) {
        delay(800)
        getNextPlayer(roomId, currentPlayer)
        activeRoom.roomData.game?.diceDenomination?.clear()
        broadcastRoomState(roomId)
        return
    }
    val latestGame = activeRoom.roomData.game
    if (latestGame != null && currentPlayer.turnsLeft == 0 &&
        !(diceDenomination!!.containsKey(1) || diceDenomination.containsKey(5))
    ) {
        if (latestGame.currentPlayer?.team == Team.RED) {
            if (latestGame.redHomeCount + latestGame.redBaseCount == latestGame.redTokens.size) {
                delay(800)
                getNextPlayer(roomId, latestGame.currentPlayer)
                activeRoom.roomData.game?.diceDenomination?.clear()
                broadcastRoomState(roomId)
            }
        } else {
            if (latestGame.blueHomeCount + latestGame.blueBaseCount == latestGame.blueTokens.size) {
                delay(800)
                getNextPlayer(roomId, latestGame.currentPlayer)
                activeRoom.roomData.game?.diceDenomination?.clear()
                broadcastRoomState(roomId)
            }
        }
    }
}

fun getTokensSetup(team: Team, count: Int): List<Token> {
    val tokens = mutableListOf<Token>()
    for (id in 1..count) {
        val token = Token(id, team, -1, GridPathCords(-1, -1))
        tokens.add(token)
    }
    return tokens
}

suspend fun spawnToken(roomId: Int?, selectedValue: Int?) {
    if (roomId == null || selectedValue == null) return
    val activeRoom = gameRooms[roomId] ?: return
    activeRoom.roomData.game?.currentPlayer?.moveTokens?.let {
        if (!it) {
            return
        }
    }
    val team = activeRoom.roomData.game?.currentPlayer?.team
    if (team == Team.RED) {
        if (activeRoom.roomData.game?.redBaseCount == 0) return
    } else {
        if (activeRoom.roomData.game?.blueBaseCount == 0) return
    }
    var diceDenomination = activeRoom.roomData.game?.diceDenomination
    diceDenomination?.containsKey(selectedValue)?.let { if (!it) return }
    diceDenomination?.put(selectedValue, diceDenomination.getValue(selectedValue).minus(1))
    if (diceDenomination?.getValue(selectedValue) == 0) {
        diceDenomination.remove(selectedValue)
    }
    if (team == Team.RED) {
        activeRoom.roomData.game?.redBaseCount -= 1
        val updatedRedTokens = activeRoom.roomData.game?.redTokens
        if (updatedRedTokens != null) {
            for (token in updatedRedTokens) {
                if (token.position == -1) {
                    token.position = 0
                    token.cords = redPath[0]
                    break
                }
            }
        }
        activeRoom.roomData.game!!.redTokens = updatedRedTokens!!
    } else {
        activeRoom.roomData.game?.blueBaseCount -= 1
        val updatedBlueTokens = activeRoom.roomData.game?.blueTokens
        if (updatedBlueTokens != null) {
            for (token in updatedBlueTokens) {
                if (token.position == -1) {
                    token.position = 0
                    token.cords = bluePath[0]
                    break
                }
            }
        }
        activeRoom.roomData.game!!.blueTokens = updatedBlueTokens!!
    }
    if (diceDenomination!!.isEmpty() && activeRoom.roomData.game!!.currentPlayer?.playerGameState != PlayerGameState.Rolling) {
        activeRoom.roomData.game!!.currentPlayer = getNextPlayer(roomId)
    }
    broadcastSpawnTokenSound(roomId)
    broadcastRoomState(roomId)

}

suspend fun moveToken(roomId: Int?, tokenId: Int?, steps: Int?) {
    if (roomId == null || tokenId == null || steps == null) return
    val activeRoom = gameRooms[roomId] ?: return
    val team = activeRoom.roomData.game?.currentPlayer?.team
    var diceDenomination = activeRoom.roomData.game?.diceDenomination
    val homeEnabled =
        if (team == Team.RED) activeRoom.roomData.game!!.redHomeEnabled else activeRoom.roomData.game!!.blueHomeEnabled
    diceDenomination?.containsKey(steps)?.let { if (!it) return }
    diceDenomination?.put(steps, diceDenomination.getValue(steps).minus(1))
    if (diceDenomination?.getValue(steps) == 0) {
        diceDenomination.remove(steps)
    }
    if (diceDenomination != null) {
        activeRoom.roomData.game!!.diceDenomination = diceDenomination
    }
    if (team == Team.RED) {
        val redTokens = activeRoom.roomData.game!!.redTokens
        val index = redTokens.indexOfFirst { it.id == tokenId }
        if (index != -1) {
            val movingToken = redTokens[index]
            movingToken.moveToken(steps, homeEnabled)
            if (movingToken.position >= redPath.size - 1) {
                activeRoom.roomData.game!!.redHomeCount += 1
                movingToken.killToken()
            }
            if (movingToken.position != -1 && !safe.contains(movingToken.cords)) {
                for (token in activeRoom.roomData.game!!.blueTokens) {
                    if (token.position != -1 && token.cords == movingToken.cords) {
                        activeRoom.roomData.game!!.redHomeEnabled = true
                        activeRoom.roomData.game!!.blueBaseCount += 1
                        activeRoom.roomData.game!!.currentPlayer!!.turnsLeft += 1
                        activeRoom.roomData.game!!.currentPlayer!!.playerGameState =
                            PlayerGameState.Rolling
                        token.killToken()
                    }
                }
            }
        }
    } else {
        val blueTokens = activeRoom.roomData.game!!.blueTokens
        val index = blueTokens.indexOfFirst { it.id == tokenId }
        if (index != -1) {
            val movingToken = blueTokens[index]
            movingToken.moveToken(steps, homeEnabled)
            if (movingToken.position >= bluePath.size - 1) {
                activeRoom.roomData.game!!.blueHomeCount += 1
                movingToken.killToken()
            }
            if (movingToken.position != -1 && !safe.contains(movingToken.cords)) {
                for (token in activeRoom.roomData.game!!.redTokens) {
                    if (token.position != -1 && token.cords == movingToken.cords) {
                        activeRoom.roomData.game!!.blueHomeEnabled = true
                        activeRoom.roomData.game!!.redBaseCount += 1
                        activeRoom.roomData.game!!.currentPlayer!!.turnsLeft += 1
                        activeRoom.roomData.game!!.currentPlayer!!.playerGameState =
                            PlayerGameState.Rolling

                        token.killToken()
                    }
                }
            }
        }
    }
    if ((activeRoom.roomData.baseTokenCount == activeRoom.roomData.game!!.redHomeCount) || (activeRoom.roomData.baseTokenCount == activeRoom.roomData.game!!.blueHomeCount)) {
        gameOver(roomId)
        return
    }
    if (activeRoom.roomData.game!!.diceDenomination.isEmpty() && activeRoom.roomData.game!!.currentPlayer?.playerGameState != PlayerGameState.Rolling && activeRoom.roomData.game!!.currentPlayer?.turnsLeft == 0) {
        activeRoom.roomData.game!!.currentPlayer =
            getNextPlayer(roomId, activeRoom.roomData.game!!.currentPlayer)
    } else {
        if (activeRoom.roomData.game!!.currentPlayer?.team == Team.RED) {
            if (activeRoom.roomData.game!!.redHomeCount + activeRoom.roomData.game!!.redBaseCount == activeRoom.roomData.game!!.redTokens.size) {
                activeRoom.roomData.game!!.currentPlayer =
                    getNextPlayer(roomId, activeRoom.roomData.game!!.currentPlayer)
            }
        } else {
            if (activeRoom.roomData.game!!.blueHomeCount + activeRoom.roomData.game!!.blueBaseCount == activeRoom.roomData.game!!.blueTokens.size) {
                activeRoom.roomData.game!!.currentPlayer =
                    getNextPlayer(roomId, activeRoom.roomData.game!!.currentPlayer)
            }
        }
    }
    val currentPlayer = activeRoom.roomData.game!!.currentPlayer
    if (currentPlayer?.playerGameState == PlayerGameState.Moving) {
        if (!canMoveToken(roomId, diceDenomination!!, currentPlayer!!.team)) {
            delay(800)
            activeRoom.roomData.game?.diceDenomination = mutableMapOf<Int, Int>()
            getNextPlayer(roomId, currentPlayer)
            broadcastRoomState(roomId)
            return
        }
    }
    broadcastRoomState(roomId)
}

suspend fun gameOver(roomId: Int) {
    val activeRoom = gameRooms[roomId] ?: return
    val gamePacket = GamePackets(gameAction = GameAction.EndGame)
    val jsonState = Json.encodeToString(gamePacket)
    activeRoom.roomData = activeRoom.roomData.copy(inGame = false)
    activeRoom.sessions.forEach { ludoSession ->
        try {
            ludoSession.session.send(Frame.Text(jsonState))
        } catch (e: Exception) {
        }
    }
}

fun stopLudoServer() {
    serverEngine?.stop(1000, 2000)
    serverEngine = null
    gameRooms.clear()
}