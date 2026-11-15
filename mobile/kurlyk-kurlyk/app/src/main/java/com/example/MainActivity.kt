package com.example

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.service.NotificationHelper
import com.example.ui.components.AppLockScreen
import com.example.ui.screens.AvatarCropScreen
import com.example.ui.screens.CallsHistoryScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.CreateGroupScreen
import com.example.ui.screens.GroupInfoScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.theme.AuroraMessengerTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object RouteSignIn
@Serializable object RouteChats
@Serializable object RouteContacts
@Serializable object RouteCalls
@Serializable object RouteSettings
@Serializable object RouteCreateGroup
@Serializable data class RouteChat(val chatId: String)
@Serializable data class RouteProfile(val phone: String)
@Serializable data class RouteGroupInfo(val groupId: String)
@Serializable data class RouteAvatarCrop(val uriString: String)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialChatId = intent?.getStringExtra(NotificationHelper.EXTRA_CHAT_ID)

        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val session by mainViewModel.session.collectAsStateWithLifecycle()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val chats by mainViewModel.chats.collectAsStateWithLifecycle()
            val users by mainViewModel.users.collectAsStateWithLifecycle()
            val callHistory by mainViewModel.callHistory.collectAsStateWithLifecycle()
            val connectionState by mainViewModel.connectionState.collectAsStateWithLifecycle()
            val wsError by mainViewModel.wsError.collectAsStateWithLifecycle()
            val signInState by mainViewModel.signInState.collectAsStateWithLifecycle()
            val isRefreshing by mainViewModel.isRefreshing.collectAsStateWithLifecycle()
            val serverConfig by mainViewModel.serverConfig.collectAsStateWithLifecycle()
            val banner by mainViewModel.statusBanner.collectAsStateWithLifecycle()

            LaunchedEffect(banner) {
                banner?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    mainViewModel.clearStatusBanner()
                }
            }

            AuroraMessengerTheme(themeMode = settings.themeMode) {
                var unlocked by remember(settings.appLockEnabled) { mutableStateOf(!settings.appLockEnabled) }

                if (session != null && settings.appLockEnabled && !unlocked) {
                    AppLockScreen(
                        expectedPinHash = settings.appLockPinHash,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val startDest: Any = if (session?.isLoggedIn == true) RouteChats else RouteSignIn

                    LaunchedEffect(session?.isLoggedIn, initialChatId) {
                        if (session?.isLoggedIn == true && !initialChatId.isNullOrBlank()) {
                            navController.navigate(RouteChat(initialChatId)) {
                                launchSingleTop = true
                            }
                        }
                    }

                    NavHost(navController = navController, startDestination = startDest) {
                        composable<RouteSignIn> {
                            SignInScreen(
                                uiState = signInState,
                                allowSelfSigned = settings.allowSelfSigned,
                                onToggleSelfSigned = mainViewModel::setAllowSelfSigned,
                                onSignIn = mainViewModel::signIn,
                            )
                            LaunchedEffect(session?.isLoggedIn) {
                                if (session?.isLoggedIn == true) {
                                    navController.navigate(RouteChats) {
                                        popUpTo(RouteSignIn) { inclusive = true }
                                    }
                                }
                            }
                        }

                        composable<RouteChats> {
                            ChatsListScreen(
                                session = session,
                                chats = chats,
                                users = users,
                                connectionState = connectionState,
                                wsError = wsError,
                                isRefreshing = isRefreshing,
                                askContactsPermission = signInState.askContactsPermission,
                                onDismissContactsPrompt = { mainViewModel.dismissContactsPrompt(true) },
                                onContactsPermissionGranted = { mainViewModel.syncContacts() },
                                onRefresh = mainViewModel::refreshDirectory,
                                onReconnect = mainViewModel::reconnectWs,
                                onOpenChat = { id -> navController.navigate(RouteChat(id)) },
                                onOpenContacts = { navController.navigate(RouteContacts) },
                                onOpenNewGroup = { navController.navigate(RouteCreateGroup) },
                                onOpenCalls = { navController.navigate(RouteCalls) },
                                onOpenSettings = { navController.navigate(RouteSettings) },
                                onTogglePin = { c -> mainViewModel.togglePin(c.chatId, c.pinned) },
                                onToggleMute = { c -> mainViewModel.toggleMute(c.chatId, c.muted) },
                                onToggleArchive = { c -> mainViewModel.toggleArchive(c.chatId, c.archived) },
                                onMarkRead = { c -> mainViewModel.markRead(c.chatId) },
                                onDeleteChat = { c -> mainViewModel.deleteChat(c.chatId) },
                            )
                        }

                        composable<RouteContacts> {
                            ContactsScreen(
                                myPhone = session?.phone.orEmpty(),
                                users = users,
                                onBack = { navController.popBackStack() },
                                onRefresh = mainViewModel::refreshDirectory,
                                onSelectUser = { phone ->
                                    scope.launch {
                                        val cid = mainViewModel.openDirectChat(phone)
                                        navController.navigate(RouteChat(cid)) {
                                            popUpTo(RouteContacts) { inclusive = true }
                                        }
                                    }
                                },
                                onNewGroup = { navController.navigate(RouteCreateGroup) },
                            )
                        }

                        composable<RouteChat> { backStackEntry ->
                            val route = backStackEntry.toRoute<RouteChat>()
                            val chatVm: ChatViewModel = hiltViewModel()
                            ChatScreen(
                                chatId = route.chatId,
                                viewModel = chatVm,
                                wallpaperId = settings.wallpaperId,
                                allChats = chats,
                                onBack = { navController.popBackStack() },
                                onOpenProfile = { phone -> navController.navigate(RouteProfile(phone)) },
                                onOpenGroupInfo = { gId -> navController.navigate(RouteGroupInfo(gId)) },
                            )
                        }

                        composable<RouteProfile> { backStackEntry ->
                            val route = backStackEntry.toRoute<RouteProfile>()
                            val chatVm: ChatViewModel = hiltViewModel()
                            LaunchedEffect(route.phone) { chatVm.setChatId(route.phone) }
                            val messages by chatVm.messages.collectAsStateWithLifecycle()
                            val filesMap by chatVm.filesMap.collectAsStateWithLifecycle()
                            val user = users.firstOrNull { it.phone == route.phone }
                            ProfileScreen(
                                user = user,
                                phone = route.phone,
                                messages = messages,
                                filesMap = filesMap,
                                onBack = { navController.popBackStack() },
                                onSendMessage = {
                                    scope.launch {
                                        mainViewModel.openDirectChat(route.phone)
                                        navController.navigate(RouteChat(route.phone)) {
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onStartCall = { video -> mainViewModel.startCall(route.phone, video) },
                            )
                        }

                        composable<RouteCreateGroup> {
                            CreateGroupScreen(
                                myPhone = session?.phone.orEmpty(),
                                users = users,
                                onBack = { navController.popBackStack() },
                                onCreate = { name, members ->
                                    mainViewModel.createGroup(name, members) { chatId ->
                                        navController.navigate(RouteChat(chatId)) {
                                            popUpTo(RouteChats)
                                        }
                                    }
                                },
                            )
                        }

                        composable<RouteGroupInfo> { backStackEntry ->
                            val route = backStackEntry.toRoute<RouteGroupInfo>()
                            val chatVm: ChatViewModel = hiltViewModel()
                            LaunchedEffect(route.groupId) { chatVm.setChatId("group:${route.groupId}") }
                            val group by chatVm.group.collectAsStateWithLifecycle()
                            val usersMap = remember(users) { users.associateBy { it.phone } }
                            GroupInfoScreen(
                                group = group,
                                myPhone = session?.phone.orEmpty(),
                                usersByPhone = usersMap,
                                onBack = { navController.popBackStack() },
                                onRename = chatVm::renameGroup,
                                onAddMembers = chatVm::addGroupMembers,
                                onRemoveMember = chatVm::removeGroupMember,
                                onLeaveGroup = {
                                    chatVm.leaveGroup { navController.popBackStack(RouteChats, inclusive = false) }
                                },
                                onDeleteGroup = {
                                    chatVm.deleteGroup { navController.popBackStack(RouteChats, inclusive = false) }
                                },
                                onOpenUserProfile = { phone -> navController.navigate(RouteProfile(phone)) },
                            )
                        }

                        composable<RouteCalls> {
                            val usersMap = remember(users) { users.associateBy { it.phone } }
                            CallsHistoryScreen(
                                callMessages = callHistory,
                                usersByPhone = usersMap,
                                onBack = { navController.popBackStack() },
                                onOpenChat = { id -> navController.navigate(RouteChat(id)) },
                                onCallBack = { phone, video -> mainViewModel.startCall(phone, video) },
                            )
                        }

                        composable<RouteSettings> {
                            val me = users.firstOrNull { it.phone == session?.phone }
                            SettingsScreen(
                                session = session,
                                me = me,
                                settings = settings,
                                serverConfig = serverConfig,
                                cacheSizeBytes = mainViewModel.getCacheSizeBytes(),
                                onBack = { navController.popBackStack() },
                                onLoadConfig = mainViewModel::loadServerConfig,
                                onPickAvatarForCrop = { uri ->
                                    navController.navigate(RouteAvatarCrop(uri.toString()))
                                },
                                onRemoveAvatar = mainViewModel::removeMyAvatar,
                                onUpdateName = mainViewModel::updateMyName,
                                onSetTheme = mainViewModel::setThemeMode,
                                onSetWallpaper = mainViewModel::setWallpaperId,
                                onSetSound = mainViewModel::setNotificationSound,
                                onSetVibration = mainViewModel::setNotificationVibration,
                                onSetAutoDownloadWifi = mainViewModel::setAutoDownloadWifi,
                                onSetAppLock = mainViewModel::setAppLock,
                                onSetAllowSelfSigned = mainViewModel::setAllowSelfSigned,
                                onClearCache = mainViewModel::clearCache,
                                onLogout = {
                                    mainViewModel.logout {
                                        navController.navigate(RouteSignIn) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                },
                            )
                        }

                        composable<RouteAvatarCrop> { backStackEntry ->
                            val route = backStackEntry.toRoute<RouteAvatarCrop>()
                            AvatarCropScreen(
                                imageUri = Uri.parse(route.uriString),
                                onCancel = { navController.popBackStack() },
                                onCropped = { jpegBytes ->
                                    mainViewModel.uploadMyAvatar(jpegBytes) {
                                        navController.popBackStack()
                                    }
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
