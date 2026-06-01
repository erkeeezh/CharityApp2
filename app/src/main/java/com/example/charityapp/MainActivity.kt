package com.example.charityapp

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.example.charityapp.data.Category
import com.example.charityapp.data.CharityItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

// ─────────────────────────────────────────────
// МОДЕЛИ
// ─────────────────────────────────────────────
data class DonationRecord(val projectTitle: String, val amount: Int, val date: String)
data class Donor(val name: String, val totalAmount: Int, val donationsCount: Int)

// Уровень пользователя
fun getUserLevel(totalAmount: Int): Triple<String, String, Int> = when {
    totalAmount >= 100000 -> Triple("💎 Меценат", "Легенда благотворительности", 100)
    totalAmount >= 50000  -> Triple("🏆 Эксперт", "Значительный вклад", (totalAmount * 100 / 100000))
    totalAmount >= 10000  -> Triple("🌟 Активист", "Ты делаешь разницу", (totalAmount * 100 / 50000))
    totalAmount >= 1000   -> Triple("🌱 Новичок", "Первые шаги в добре", (totalAmount * 100 / 10000))
    else                  -> Triple("👤 Гость", "Сделай первый донат!", 0)
}

// ─────────────────────────────────────────────
// ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ ДЛЯ КАМЕРЫ
// ─────────────────────────────────────────────
fun createImageFile(context: Context): Uri {
    val imageDir = File(context.cacheDir, "images").also { it.mkdirs() }
    val file = File.createTempFile("profile_", ".jpg", imageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}

// ─────────────────────────────────────────────
// AUTH VIEW MODEL
// ─────────────────────────────────────────────
class AuthViewModel : ViewModel() {
    private val auth: FirebaseAuth = Firebase.auth
    private val db = Firebase.database.reference

    // Текущий пользователь (null = не вошёл)
    var currentUser by mutableStateOf(auth.currentUser)
        private set

    // Имя пользователя, загружается из БД
    var displayName by mutableStateOf("")
        private set

    // Флаги загрузки и ошибки
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        // Загружаем displayName если уже вошли
        currentUser?.let { loadDisplayName(it.uid) }
    }

    // ── РЕГИСТРАЦИЯ ──────────────────────────
    suspend fun register(email: String, password: String, name: String): Boolean {
        isLoading = true
        errorMessage = null
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw Exception("Пользователь не создан")

            // Сохраняем профиль в Realtime Database
            val profile = mapOf(
                "uid"      to user.uid,
                "email"    to email,
                "name"     to name.ifBlank { email.substringBefore("@") },
                "password" to password,
                "totalDonated"   to 0,
                "donationsCount" to 0,
                "createdAt" to System.currentTimeMillis()
            )
            db.child("users").child(user.uid).setValue(profile).await()

            currentUser = user
            displayName = name.ifBlank { email.substringBefore("@") }
            true
        } catch (e: FirebaseAuthWeakPasswordException) {
            errorMessage = "Пароль слишком слабый (минимум 6 символов)"
            false
        } catch (e: FirebaseAuthUserCollisionException) {
            errorMessage = "Этот email уже зарегистрирован"
            false
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            errorMessage = "Неверный формат email"
            false
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Ошибка регистрации"
            false
        } finally {
            isLoading = false
        }
    }

    // ── ВХОД ─────────────────────────────────
    suspend fun login(email: String, password: String): Boolean {
        isLoading = true
        errorMessage = null
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw Exception("Ошибка входа")
            currentUser = user
            loadDisplayName(user.uid)
            true
        } catch (e: FirebaseAuthInvalidUserException) {
            errorMessage = "Пользователь с таким email не найден"
            false
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            errorMessage = "Неверный email или пароль"
            false
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Ошибка входа"
            false
        } finally {
            isLoading = false
        }
    }

    // ── ВЫХОД ────────────────────────────────
    fun logout() {
        auth.signOut()
        currentUser = null
        displayName = ""
    }

    fun clearError() { errorMessage = null }

    private fun loadDisplayName(uid: String) {
        db.child("users").child(uid).child("name").get()
            .addOnSuccessListener { snap ->
                displayName = snap.getValue(String::class.java)
                    ?: currentUser?.email?.substringBefore("@") ?: "Пользователь"
            }
    }
}

// ─────────────────────────────────────────────
// USER VIEW MODEL (без изменений, userName синхронизирован с Auth)
// ─────────────────────────────────────────────
class UserViewModel : ViewModel() {
    private val db = Firebase.database.reference
    var totalDonatedAmount by mutableIntStateOf(0)
    var totalDonationsCount by mutableIntStateOf(0)
    var userName by mutableStateOf("Пользователь #12")
    var notificationsEnabled by mutableStateOf(true)
    var paymentMethod by mutableStateOf("Kaspi Gold •• 4321")
    var profilePhotoUri by mutableStateOf<Uri?>(null)
    var pendingTopNotification by mutableStateOf<String?>(null)

    val donationHistory = mutableStateListOf<DonationRecord>()

    val donors = mutableStateListOf(
        Donor("Айгерим К.", 120000, 14),
        Donor("Тимур М.", 98500, 11),
        Donor("Сауле Н.", 75000, 9),
        Donor("Данияр Б.", 60000, 7),
        Donor("Жанна Р.", 45000, 6),
        Donor("Арман С.", 32000, 4),
        Donor("Пользователь #12", 0, 0),
        Donor("Нурлан Е.", 18000, 2),
        Donor("Алия Т.", 12000, 1),
        Donor("Берик О.", 5000, 1),
    )

    val projectGoals = mutableStateMapOf(
        "proj1" to 500000,
        "proj2" to 300000,
        "proj3" to 200000,
        "proj4" to 800000
    )

    val projects = mutableStateListOf<CharityItem>()

    init {
        loadProjectsFromFirebase()
        loadDonorsFromFirebase()
    }

    private fun loadProjectsFromFirebase() {
        db.child("projects").get().addOnSuccessListener { snapshot ->
            val loaded = snapshot.children.mapNotNull { snap ->
                val id          = snap.key ?: return@mapNotNull null
                val title       = snap.child("title").getValue(String::class.java) ?: ""
                val location    = snap.child("location").getValue(String::class.java) ?: ""
                val description = snap.child("description").getValue(String::class.java) ?: ""
                val imageUrl    = snap.child("imageUrl").getValue(String::class.java) ?: ""
                val goal        = snap.child("goal").getValue(String::class.java) ?: ""
                val progress    = snap.child("progress").getValue(Double::class.java)?.toFloat() ?: 0f
                val goalAmount  = snap.child("goalAmount").getValue(Int::class.java) ?: 500000
                val category    = try {
                    Category.valueOf(snap.child("category").getValue(String::class.java) ?: "CHILDREN")
                } catch (e: Exception) { Category.CHILDREN }
                projectGoals[id] = goalAmount
                CharityItem(id, title, location, description, imageUrl, goal, progress, category)
            }
            projects.clear()
            projects.addAll(loaded)
        }
    }

    private fun loadDonorsFromFirebase() {
        db.child("donors").get().addOnSuccessListener { snapshot ->
            val loaded = snapshot.children.mapNotNull { snap ->
                val name  = snap.child("name").getValue(String::class.java) ?: return@mapNotNull null
                val total = snap.child("totalAmount").getValue(Int::class.java) ?: 0
                val count = snap.child("donationsCount").getValue(Int::class.java) ?: 0
                Donor(name, total, count)
            }
            if (loaded.isNotEmpty()) {
                donors.clear()
                donors.addAll(loaded.sortedByDescending { it.totalAmount })
            }
        }
    }

    fun contribute(projectId: String, amount: Int) {
        val rankBefore = donors.indexOfFirst { it.name == userName } + 1
        totalDonatedAmount += amount
        totalDonationsCount += 1

        val index = projects.indexOfFirst { it.id == projectId }
        if (index != -1) {
            val item = projects[index]
            val goal = projectGoals[projectId] ?: 500000
            val newProgress = (item.progress + (amount.toFloat() / goal.toFloat())).coerceAtMost(1f)
            projects[index] = item.copy(progress = newProgress)
            donationHistory.add(0, DonationRecord(item.title, amount, "сегодня"))
        }

        val userIndex = donors.indexOfFirst { it.name == userName }
        if (userIndex != -1) {
            val u = donors[userIndex]
            donors[userIndex] = u.copy(totalAmount = u.totalAmount + amount, donationsCount = u.donationsCount + 1)
        }

        val sorted = donors.sortedByDescending { it.totalAmount }
        donors.clear()
        donors.addAll(sorted)

        val rankAfter = donors.indexOfFirst { it.name == userName } + 1
        if (rankAfter in 1..3 && (rankBefore > 3 || rankBefore > rankAfter)) {
            val medal = when (rankAfter) { 1 -> "🥇"; 2 -> "🥈"; else -> "🥉" }
            pendingTopNotification = "$medal Вы поднялись на $rankAfter место в рейтинге!"
        }

        // Сохраняем донат в Firebase
        val uid = Firebase.auth.currentUser?.uid ?: return

        val projectTitle = projects.find { it.id == projectId }?.title ?: ""

        // Запись доната
        val donationData = mapOf(
            "userId"       to uid,
            "projectId"    to projectId,
            "projectTitle" to projectTitle,
            "amount"       to amount,
            "date"         to System.currentTimeMillis()
        )
        db.child("donations").push().setValue(donationData)

        // Обновляем прогресс проекта
        val updatedProgress = projects.find { it.id == projectId }?.progress?.toDouble() ?: 0.0
        db.child("projects").child(projectId).child("progress").setValue(updatedProgress)

        // Обновляем статистику пользователя
        db.child("users").child(uid).child("totalDonated")
            .get().addOnSuccessListener { snap ->
                val prev = snap.getValue(Int::class.java) ?: 0
                db.child("users").child(uid).child("totalDonated").setValue(prev + amount)
            }
        db.child("users").child(uid).child("donationsCount")
            .get().addOnSuccessListener { snap ->
                val prev = snap.getValue(Int::class.java) ?: 0
                db.child("users").child(uid).child("donationsCount").setValue(prev + 1)
            }
    }

    fun clearTopNotification() { pendingTopNotification = null }

    fun updateUserName(newName: String) {
        val userIndex = donors.indexOfFirst { it.name == userName }
        if (userIndex != -1) {
            val u = donors[userIndex]
            donors[userIndex] = u.copy(name = newName)
        }
        userName = newName
    }
}

// ─────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val authViewModel: AuthViewModel = viewModel()
            val userViewModel: UserViewModel = viewModel()

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF4CAF50),
                    secondary = Color(0xFFE91E63),
                    surface = Color(0xFFFDFDFD)
                )
            ) {
                // Если authViewModel.currentUser == null → показываем Auth-граф
                // Если вошли → показываем основной граф
                if (authViewModel.currentUser == null) {
                    AuthNavGraph(authViewModel)
                } else {
                    // Синхронизируем имя пользователя из Firebase в UserViewModel
                    LaunchedEffect(authViewModel.displayName) {
                        if (authViewModel.displayName.isNotBlank()) {
                            userViewModel.updateUserName(authViewModel.displayName)
                        }
                    }
                    MainAppGraph(userViewModel, authViewModel)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// AUTH NAV GRAPH (Login / Register / Welcome)
// ─────────────────────────────────────────────
@Composable
fun AuthNavGraph(authViewModel: AuthViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "login") {
        composable("login")    { LoginScreen(authViewModel, navController) }
        composable("register") { RegisterScreen(authViewModel, navController) }
        composable("welcome")  { WelcomeScreen(authViewModel) }
    }
}

// ─────────────────────────────────────────────
// ЭКРАН ВХОДА
// ─────────────────────────────────────────────
@Composable
fun LoginScreen(vm: AuthViewModel, navController: NavController) {
    var email    by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    // Очищаем ошибку при смене ввода
    LaunchedEffect(email, password) { vm.clearError() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .verticalScroll(rememberScrollState())
    ) {
        // Шапка с градиентом
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF8BC34A)))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.25f)
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.padding(18.dp),
                        tint = Color.White
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Добрые дела", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("Войдите, чтобы помогать", fontSize = 14.sp, color = Color.White.copy(0.85f))
            }
        }

        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // Блок ошибки
            vm.errorMessage?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(err, color = Color(0xFFE53935), fontSize = 13.sp)
                    }
                }
            }

            AuthTextField(
                value    = email,
                onValueChange = { email = it },
                label    = "Email",
                icon     = Icons.Default.Email,
                keyboardType = KeyboardType.Email
            )

            OutlinedTextField(
                value         = password,
                onValueChange = { password = it },
                label         = { Text("Пароль") },
                leadingIcon   = { Icon(Icons.Default.Lock, null, tint = Color(0xFF4CAF50)) },
                trailingIcon  = {
                    IconButton(onClick = { passVisible = !passVisible }) {
                        Icon(
                            if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }
                },
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(14.dp),
                singleLine = true
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    scope.launch {
                        val ok = vm.login(email.trim(), password)
                        // Если вошли — currentUser станет != null, граф переключится автоматически
                        // Если нет — errorMessage уже установлен
                        if (ok) navController.navigate("welcome") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                },
                enabled  = email.isNotBlank() && password.isNotBlank() && !vm.isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape    = RoundedCornerShape(18.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                if (vm.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Войти", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Нет аккаунта? ", color = Color.Gray, fontSize = 14.sp)
                Text(
                    "Зарегистрироваться",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { navController.navigate("register") }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// ЭКРАН РЕГИСТРАЦИИ
// ─────────────────────────────────────────────
@Composable
fun RegisterScreen(vm: AuthViewModel, navController: NavController) {
    var name         by remember { mutableStateOf("") }
    var email        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var confirmPass  by remember { mutableStateOf("") }
    var passVisible  by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()

    // Локальная валидация
    val passwordsMatch = password == confirmPass || confirmPass.isEmpty()
    val canSubmit = name.isNotBlank() && email.isNotBlank() &&
            password.length >= 6 && password == confirmPass && !vm.isLoading

    LaunchedEffect(name, email, password, confirmPass) { vm.clearError() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .verticalScroll(rememberScrollState())
    ) {
        // Шапка
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF2196F3), Color(0xFF4CAF50)))),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.White.copy(0.2f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Создать аккаунт", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("Присоединяйтесь к сообществу добра", fontSize = 13.sp, color = Color.White.copy(0.85f))
            }
        }

        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Ошибка Firebase
            vm.errorMessage?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(err, color = Color(0xFFE53935), fontSize = 13.sp)
                    }
                }
            }

            AuthTextField(
                value = name,
                onValueChange = { name = it },
                label = "Имя и фамилия",
                icon  = Icons.Default.Person
            )

            AuthTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email",
                icon  = Icons.Default.Email,
                keyboardType = KeyboardType.Email
            )

            // Поле пароля
            OutlinedTextField(
                value         = password,
                onValueChange = { password = it },
                label         = { Text("Пароль (минимум 6 символов)") },
                leadingIcon   = { Icon(Icons.Default.Lock, null, tint = Color(0xFF4CAF50)) },
                trailingIcon  = {
                    IconButton(onClick = { passVisible = !passVisible }) {
                        Icon(
                            if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }
                },
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(14.dp),
                singleLine = true,
                isError   = password.isNotEmpty() && password.length < 6,
                supportingText = if (password.isNotEmpty() && password.length < 6) {
                    { Text("Пароль должен быть не менее 6 символов", color = MaterialTheme.colorScheme.error) }
                } else null
            )

            // Подтверждение пароля
            OutlinedTextField(
                value         = confirmPass,
                onValueChange = { confirmPass = it },
                label         = { Text("Повторите пароль") },
                leadingIcon   = { Icon(Icons.Default.Lock, null, tint = Color(0xFF4CAF50)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(14.dp),
                singleLine = true,
                isError   = !passwordsMatch,
                supportingText = if (!passwordsMatch) {
                    { Text("Пароли не совпадают", color = MaterialTheme.colorScheme.error) }
                } else null
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    scope.launch {
                        val ok = vm.register(email.trim(), password, name.trim())
                        if (ok) navController.navigate("welcome") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                },
                enabled  = canSubmit,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape    = RoundedCornerShape(18.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                if (vm.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Зарегистрироваться", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Уже есть аккаунт? ", color = Color.Gray, fontSize = 14.sp)
                Text(
                    "Войти",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { navController.popBackStack() }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────
// ЭКРАН ПРИВЕТСТВИЯ (после входа/регистрации)
// ─────────────────────────────────────────────
@Composable
fun WelcomeScreen(authViewModel: AuthViewModel) {
    // Через 0.5 секунды «переключается» на главный граф — currentUser уже != null,
    // поэтому MainActivity автоматически покажет MainAppGraph.
    // WelcomeScreen — промежуточный красивый экран.
    val name = authViewModel.displayName.ifBlank {
        authViewModel.currentUser?.email?.substringBefore("@") ?: "друг"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF8BC34A)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("👋", fontSize = 72.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Добро пожаловать,",
                fontSize = 20.sp,
                color = Color.White.copy(0.9f),
                textAlign = TextAlign.Center
            )
            Text(
                name,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Вы присоединились к сообществу людей,\nкоторые меняют мир к лучшему 💚",
                fontSize = 15.sp,
                color = Color.White.copy(0.85f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(48.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.2f))
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌍  4 активных проекта", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("👥  12 000+ доноров рядом с вами", color = Color.White.copy(0.9f), fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("💰  Каждый тенге идёт напрямую", color = Color.White.copy(0.9f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(40.dp))
            // Автопереход — currentUser уже установлен, MainActivity переключит граф
            // Добавим кнопку для мгновенного перехода
            Button(
                onClick = { /* currentUser != null → MainActivity покажет MainAppGraph */ },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text(
                    "Начать помогать →",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// ВСПОМОГАТЕЛЬНЫЙ Composable для полей Auth
// ─────────────────────────────────────────────
@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label) },
        leadingIcon   = { Icon(icon, null, tint = Color(0xFF4CAF50)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(14.dp),
        singleLine = true
    )
}

// ─────────────────────────────────────────────
// ОСНОВНОЙ ГРАФ (уже вошли)
// ─────────────────────────────────────────────
@Composable
fun MainAppGraph(userViewModel: UserViewModel, authViewModel: AuthViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val topNotification = userViewModel.pendingTopNotification
    LaunchedEffect(topNotification) {
        if (topNotification != null) {
            snackbarHostState.showSnackbar(message = topNotification, duration = SnackbarDuration.Long)
            userViewModel.clearTopNotification()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF1B5E20),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 12.dp) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val items = listOf(
                    Triple("list", "Проекты", Icons.Default.FavoriteBorder),
                    Triple("leaderboard", "Рейтинг", Icons.Default.Star),
                    Triple("about", "О нас", Icons.Default.Info),
                    Triple("profile", "Профиль", Icons.Default.Person)
                )

                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, fontSize = 10.sp) },
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, "list", Modifier.padding(innerPadding)) {
            composable("list")        { CharityList(userViewModel.projects, userViewModel.projectGoals, navController) }
            composable("leaderboard") { LeaderboardScreen(userViewModel) }
            composable("about")       { AboutScreen(navController) }
            // Передаём authViewModel для кнопки выхода
            composable("profile")  { ProfileScreen(userViewModel, navController, authViewModel) }
            composable("settings") { SettingsScreen(userViewModel, navController, authViewModel) }
            composable("join_team")   { JoinTeamScreen(navController) }
            composable("add_item")    { AddItemScreen(navController) }
            composable(
                "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")
                val project = userViewModel.projects.find { it.id == id }
                project?.let { TaskDetailScreen(it, navController, userViewModel, snackbarHostState) }
            }
        }
    }
}

// ─────────────────────────────────────────────
// СПИСОК ПРОЕКТОВ (без изменений)
// ─────────────────────────────────────────────
@Composable
fun CharityList(items: List<CharityItem>, goals: Map<String, Int>, navController: NavController) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FA)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("Добрые дела", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text("Твой вклад меняет мир сегодня", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF8BC34A))))
                        .padding(18.dp)
                ) {
                    Column {
                        Text("🌍 Вместе мы помогли", color = Color.White.copy(0.85f), fontSize = 13.sp)
                        Text("4 проектам в Алматы", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        items(items) { item ->
            val goal = goals[item.id] ?: 500000
            val collected = (item.progress * goal).toInt()
            CharityCard(item, collected, goal) { navController.navigate("detail/${item.id}") }
        }
    }
}

@Composable
fun CharityCard(charity: CharityItem, collected: Int, goal: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
        onClick = onClick
    ) {
        Column {
            Box {
                AsyncImage(
                    model = charity.imageUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(180.dp)
                )
                Text(
                    charity.category.name,
                    modifier = Modifier.padding(12.dp).background(Color.White, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp).align(Alignment.TopStart),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(charity.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(charity.location, color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { charity.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatAmount(collected)} ₸ из ${formatAmount(goal)} ₸", fontSize = 12.sp, color = Color.Gray)
                    Text("${(charity.progress * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun formatAmount(amount: Int): String {
    return if (amount >= 1000) "${amount / 1000} ${if (amount % 1000 != 0) ".${(amount % 1000) / 100}" else ""}тыс"
    else amount.toString()
}

// ─────────────────────────────────────────────
// РЕЙТИНГ (без изменений)
// ─────────────────────────────────────────────
@Composable
fun LeaderboardScreen(vm: UserViewModel) {
    val topThree = vm.donors.take(3)
    val rest = vm.donors.drop(3)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FA)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("Рейтинг доноров", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text("Люди, которые меняют мир", color = Color.Gray)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                if (topThree.size > 1) PodiumCard(topThree[1], 2, 80.dp)
                if (topThree.isNotEmpty()) PodiumCard(topThree[0], 1, 110.dp)
                if (topThree.size > 2) PodiumCard(topThree[2], 3, 60.dp)
            }
        }
        item { Text("Все участники", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        itemsIndexed(rest) { index, donor ->
            LeaderboardRow(donor, index + 4, donor.name == vm.userName)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun PodiumCard(donor: Donor, place: Int, height: androidx.compose.ui.unit.Dp) {
    val medalColor = when (place) { 1 -> Color(0xFFFFD700); 2 -> Color(0xFFB0BEC5); else -> Color(0xFFCD7F32) }
    val medal = when (place) { 1 -> "🥇"; 2 -> "🥈"; else -> "🥉" }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Text(medal, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(donor.name.split(" ").firstOrNull() ?: donor.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("${donor.totalAmount} ₸", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(medalColor.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) { Text("$place", fontWeight = FontWeight.Black, fontSize = 24.sp, color = medalColor) }
    }
}

@Composable
fun LeaderboardRow(donor: Donor, position: Int, isCurrentUser: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isCurrentUser) Color(0xFFE8F5E9) else Color.White),
        elevation = CardDefaults.cardElevation(if (isCurrentUser) 4.dp else 1.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#$position", fontWeight = FontWeight.Black, fontSize = 16.sp,
                color = if (isCurrentUser) Color(0xFF4CAF50) else Color.Gray, modifier = Modifier.width(40.dp))
            Surface(Modifier.size(40.dp), shape = CircleShape, color = Color(0xFFF1F1F1)) {
                Icon(Icons.Default.Person, null, Modifier.padding(8.dp), tint = Color.Gray)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(donor.name + if (isCurrentUser) " (вы)" else "",
                    fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                Text("${donor.donationsCount} донатов", fontSize = 12.sp, color = Color.Gray)
            }
            Text("${donor.totalAmount} ₸", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
        }
    }
}

// ─────────────────────────────────────────────
// О НАС (без изменений)
// ─────────────────────────────────────────────
@Composable
fun AboutScreen(navController: NavController) {
    val teamMembers = listOf(
        Triple("Асель Нурланова", "Директор фонда", "🏛"),
        Triple("Марат Сейткали", "Руководитель проектов", "📋"),
        Triple("Дина Ахметова", "Координатор волонтёров", "🤝"),
        Triple("Айбек Жумабеков", "IT & Разработка", "💻"),
        Triple("Зарина Оспанова", "PR & Коммуникации", "📢"),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FA)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("О нас", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("Фонд «Добрые дела»", color = Color.Gray)
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp)) {
                    Text("🌟 Наша миссия", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Мы объединяем неравнодушных людей Алматы для поддержки детей, пожилых, животных и экологии. " +
                                "С 2019 года фонд реализовал более 50 проектов и помог тысячам людей.",
                        color = Color.DarkGray, lineHeight = 22.sp
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutStatCard("50+", "Проектов", Modifier.weight(1f))
                AboutStatCard("12 000+", "Доноров", Modifier.weight(1f))
                AboutStatCard("5 лет", "Работы", Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9))) {
                Column(Modifier.padding(20.dp)) {
                    Text("💡 Зачем помогать?", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF388E3C))
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "Каждый донат напрямую идёт в проект — без посредников",
                        "Вы видите прогресс сбора в реальном времени",
                        "Отчёты о расходовании средств публикуются ежемесячно",
                        "Налоговый вычет для юридических лиц"
                    ).forEach { point ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("✓ ", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Text(point, fontSize = 13.sp, color = Color.DarkGray)
                        }
                    }
                }
            }
        }
        item { Text("Наша команда", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        items(teamMembers) { (name, role, emoji) -> TeamMemberCard(name, role, emoji) }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { navController.navigate("join_team") },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Стать частью команды", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun AboutStatCard(value: String, label: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color(0xFF4CAF50))
            Text(label, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun TeamMemberCard(name: String, role: String, emoji: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = Color(0xFFE8F5E9)) {
                Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 22.sp) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(role, fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

// ─────────────────────────────────────────────
// JOIN TEAM (без изменений)
// ─────────────────────────────────────────────
@Composable
fun JoinTeamScreen(navController: NavController) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF8BC34A))))
        ) {
            IconButton(onClick = { navController.popBackStack() },
                Modifier.padding(12.dp).background(Color.White.copy(0.2f), CircleShape).align(Alignment.TopStart)) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🤝 Стать частью команды", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("Оставьте заявку — мы свяжемся с вами", fontSize = 13.sp, color = Color.White.copy(0.85f))
            }
        }

        if (submitted) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("✅", fontSize = 64.sp)
                Spacer(Modifier.height(16.dp))
                Text("Заявка отправлена!", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Мы рассмотрим вашу заявку и свяжемся с вами в течение 3 рабочих дней.",
                    color = Color.Gray, lineHeight = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { navController.popBackStack() }, shape = RoundedCornerShape(14.dp)) {
                    Text("Вернуться назад")
                }
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                JoinTextField("Ваше имя и фамилия", name, Icons.Default.Person) { name = it }
                JoinTextField("Желаемая роль (волонтёр, специалист...)", role, Icons.Default.Build) { role = it }
                JoinTextField("Контакт (телефон или email)", contact, Icons.Default.Phone) { contact = it }
                OutlinedTextField(
                    value = about, onValueChange = { about = it },
                    label = { Text("Расскажите о себе / прикрепите резюме-текст") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 6
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { if (name.isNotBlank() && contact.isNotBlank()) submitted = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = name.isNotBlank() && contact.isNotBlank()
                ) { Text("Отправить заявку", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun JoinTextField(label: String, value: String, icon: ImageVector, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = Color(0xFF4CAF50)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = true
    )
}

// ─────────────────────────────────────────────
// ДЕТАЛИ ПРОЕКТА (без изменений)
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    project: CharityItem,
    navController: NavController,
    vm: UserViewModel,
    snackbarHostState: SnackbarHostState
) {
    var showSheet by remember { mutableStateOf(false) }
    val goal = vm.projectGoals[project.id] ?: 500000
    val collected = (project.progress * goal).toInt()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            AsyncImage(project.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = { navController.popBackStack() },
                Modifier.padding(16.dp).background(Color.White, CircleShape).align(Alignment.TopStart)
            ) { Icon(Icons.Default.ArrowBack, null) }
        }
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(project.category.name.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(project.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { project.progress },
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Собрано: ${formatAmount(collected)} ₸", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4CAF50))
                Text("Цель: ${formatAmount(goal)} ₸", fontSize = 13.sp, color = Color.Gray)
            }
            Text("${(project.progress * 100).toInt()}% выполнено", fontSize = 12.sp, color = Color.Gray)

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoChip("📍 ${project.location}", Modifier.weight(1f))
                InfoChip("🏷 ${project.goal}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Text("О проекте", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(project.description, color = Color.DarkGray, lineHeight = 22.sp)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { showSheet = true },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text("ПОДДЕРЖАТЬ ПРОЕКТ", fontWeight = FontWeight.Black)
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            DonationForm { amount ->
                vm.contribute(project.id, amount)
                showSheet = false
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "✅ Оплата прошла успешно! Спасибо за донат ${amount} ₸",
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }
    }
}

@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F8E9)).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF388E3C)) }
}

// ─────────────────────────────────────────────
// ПРОФИЛЬ — добавлен authViewModel для email и выхода
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: UserViewModel, navController: NavController, authViewModel: AuthViewModel) {
    val context = LocalContext.current
    val userRank = vm.donors.indexOfFirst { it.name == vm.userName } + 1
    val (levelName, levelDesc, levelProgress) = getUserLevel(vm.totalDonatedAmount)
    var showEditName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(vm.userName) }
    var showPhotoDialog by remember { mutableStateOf(false) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.profilePhotoUri = uri
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success && cameraImageUri != null) vm.profilePhotoUri = cameraImageUri
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (granted) {
            val uri = createImageFile(context)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        }
    }
    val launchCamera: () -> Unit = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }

    // Email из Firebase
    val userEmail = authViewModel.currentUser?.email ?: ""

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).verticalScroll(rememberScrollState())) {
        Box(
            modifier = Modifier.fillMaxWidth().height(210.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF8BC34A))))
        ) {
            IconButton(
                onClick = { navController.navigate("settings") },
                Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) { Icon(Icons.Default.Settings, null, tint = Color.White) }

            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.size(80.dp)) {
                    Surface(
                        Modifier.size(72.dp).align(Alignment.Center),
                        shape = CircleShape,
                        color = Color.White.copy(0.3f)
                    ) {
                        if (vm.profilePhotoUri != null) {
                            AsyncImage(
                                model = vm.profilePhotoUri, contentDescription = "Фото профиля",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Icon(Icons.Default.Person, null, Modifier.padding(18.dp), tint = Color.White)
                        }
                    }
                    Surface(
                        modifier = Modifier.size(26.dp).clickable { showPhotoDialog = true },
                        shape = CircleShape, color = Color.White
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить фото",
                            tint = Color(0xFF4CAF50), modifier = Modifier.padding(4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.userName, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Edit, null, tint = Color.White.copy(0.8f),
                        modifier = Modifier.size(16.dp).clickable { editNameValue = vm.userName; showEditName = true })
                }
                // Показываем email из Firebase
                if (userEmail.isNotBlank()) {
                    Text(userEmail, color = Color.White.copy(0.85f), fontSize = 12.sp)
                }
                Text("Алматы, Казахстан", color = Color.White.copy(0.7f), fontSize = 12.sp)
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Сумма", "${vm.totalDonatedAmount} ₸", Modifier.weight(1f))
                StatCard("Донатов", "${vm.totalDonationsCount}", Modifier.weight(1f))
                StatCard("Место", "#$userRank", Modifier.weight(1f))
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(levelName, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Text("$levelProgress%", fontSize = 13.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                    Text(levelDesc, fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { levelProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            vm.totalDonatedAmount < 1000 -> "До след. уровня: ${1000 - vm.totalDonatedAmount} ₸"
                            vm.totalDonatedAmount < 10000 -> "До след. уровня: ${10000 - vm.totalDonatedAmount} ₸"
                            vm.totalDonatedAmount < 50000 -> "До след. уровня: ${50000 - vm.totalDonatedAmount} ₸"
                            vm.totalDonatedAmount < 100000 -> "До след. уровня: ${100000 - vm.totalDonatedAmount} ₸"
                            else -> "Максимальный уровень достигнут! 🎉"
                        },
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }

            Text("Достижения", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BadgeCard("🌱", "Первый шаг", vm.totalDonationsCount >= 1, Modifier.weight(1f))
                BadgeCard("🏙", "Герой города", vm.totalDonationsCount >= 5, Modifier.weight(1f))
                BadgeCard("💎", "Меценат", vm.totalDonatedAmount >= 50000, Modifier.weight(1f))
            }

            Text("История донатов", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (vm.donationHistory.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Вы ещё не делали донатов 🙂", color = Color.Gray, fontSize = 14.sp) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.donationHistory.forEach { DonationHistoryRow(it) }
                }
            }
        }
    }

    // Диалог фото
    if (showPhotoDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoDialog = false },
            title = { Text("Фото профиля", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF1F8E9))
                            .clickable { showPhotoDialog = false; galleryLauncher.launch("image/*") }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.size(42.dp), shape = CircleShape, color = Color(0xFF4CAF50)) {
                            Icon(Icons.Default.Add, null, Modifier.padding(10.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Выбрать из галереи", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Открыть фотографии устройства", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF1F8E9))
                            .clickable { showPhotoDialog = false; launchCamera() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.size(42.dp), shape = CircleShape, color = Color(0xFF4CAF50)) {
                            Icon(Icons.Default.Face, null, Modifier.padding(10.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Сделать фото", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Открыть камеру устройства", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPhotoDialog = false }) { Text("Отмена") } },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Диалог имени
    if (showEditName) {
        AlertDialog(
            onDismissRequest = { showEditName = false },
            title = { Text("Изменить имя") },
            text = {
                OutlinedTextField(
                    value = editNameValue, onValueChange = { editNameValue = it },
                    label = { Text("Ваше имя") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editNameValue.isNotBlank()) vm.updateUserName(editNameValue)
                    showEditName = false
                }) { Text("Сохранить", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEditName = false }) { Text("Отмена") } }
        )
    }
}

// ─────────────────────────────────────────────
// НАСТРОЙКИ — добавлена кнопка выхода через Firebase
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: UserViewModel, navController: NavController, authViewModel: AuthViewModel) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var paymentInput by remember { mutableStateOf(vm.paymentMethod) }

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA))) {
        Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, null)
            }
            Text("Настройки", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Блок: аккаунт Firebase
            Text("Аккаунт", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), shape = CircleShape, color = Color(0xFFE8F5E9)) {
                            Icon(Icons.Default.Person, null, Modifier.padding(10.dp), tint = Color(0xFF4CAF50))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(vm.userName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                authViewModel.currentUser?.email ?: "",
                                fontSize = 12.sp, color = Color.Gray
                            )
                        }
                    }
                }
            }

            Text("Уведомления", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray)
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Push-уведомления", fontWeight = FontWeight.Medium)
                        Text("Новости проектов и обновления", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = vm.notificationsEnabled,
                        onCheckedChange = { vm.notificationsEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50), checkedTrackColor = Color(0xFFE8F5E9))
                    )
                }
            }

            Text("Оплата", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray)
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).clickable { paymentInput = vm.paymentMethod; showPaymentDialog = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ShoppingCart, null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Метод оплаты", fontWeight = FontWeight.Medium)
                        Text(vm.paymentMethod, fontSize = 12.sp, color = Color.Gray)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                }
            }

            Text("Безопасность", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray)
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).clickable { showLogoutDialog = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ExitToApp, null, tint = Color(0xFFE91E63))
                    Spacer(Modifier.width(14.dp))
                    Text("Выйти из аккаунта", fontWeight = FontWeight.Medium, color = Color(0xFFE91E63))
                }
            }
        }
    }

    // Диалог выхода
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Выход", fontWeight = FontWeight.Bold) },
            text = { Text("Вы уверены, что хотите выйти из аккаунта?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    authViewModel.logout()
                    // currentUser станет null → MainActivity переключит граф на AuthNavGraph
                }) {
                    Text("Выйти", color = Color(0xFFE91E63), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } }
        )
    }

    // Диалог смены оплаты
    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("Метод оплаты") },
            text = {
                OutlinedTextField(
                    value = paymentInput, onValueChange = { paymentInput = it },
                    label = { Text("Название карты / кошелька") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.paymentMethod = paymentInput; showPaymentDialog = false }) {
                    Text("Сохранить", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showPaymentDialog = false }) { Text("Отмена") } }
        )
    }
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)) { content() }
}

// ─────────────────────────────────────────────
// ВСПОМОГАТЕЛЬНЫЕ COMPOSABLE (без изменений)
// ─────────────────────────────────────────────
@Composable
fun BadgeCard(emoji: String, label: String, unlocked: Boolean, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (unlocked) Color(0xFFFFF9C4) else Color(0xFFF1F1F1)),
        elevation = CardDefaults.cardElevation(if (unlocked) 3.dp else 0.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (unlocked) emoji else "🔒", fontSize = 26.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (unlocked) Color(0xFF795548) else Color.Gray)
        }
    }
}

@Composable
fun DonationHistoryRow(record: DonationRecord) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), shape = CircleShape, color = Color(0xFFE8F5E9)) {
                Icon(Icons.Default.Favorite, null, Modifier.padding(10.dp), tint = Color(0xFF4CAF50))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.projectTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(record.date, fontSize = 12.sp, color = Color.Gray)
            }
            Text("+${record.amount} ₸", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFF4CAF50))
            Text(label, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun DonationForm(onDonateSuccess: (Int) -> Unit) {
    var amount by remember { mutableStateOf("5000") }
    Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
        Text("Сумма пожертвования", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Любая сумма имеет значение 💚", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1000", "5000", "10000").forEach { preset ->
                OutlinedButton(
                    onClick = { amount = preset }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (amount == preset) Color(0xFFE8F5E9) else Color.Transparent)
                ) { Text("$preset ₸", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("Или введите свою сумму (₸)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onDonateSuccess(amount.toIntOrNull() ?: 0) },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Подтвердить", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun AddItemScreen(navController: NavController) {}