package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.ConfigKey
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.DataEntity
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.BROKEN
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.PROBABLE
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.UNVERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.VERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.RelationsSummary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fase 5B: conexiones propias de cada framework. Cada proyecto de prueba tiene conexiones conocidas que deben
 * encontrarse con su confianza y su evidencia, y trampas que no deben convertirse en conexiones inventadas.
 */
class FrameworkRelationsTest {

    @TempDir
    lateinit var dir: File

    /** `¤` en lugar de `$` para escribir código de JavaScript, PHP y Kotlin sin escapar. */
    private fun project(vararg files: Pair<String, String>): Run = analyzeProject(dir, files.associate { (k, v) -> k to v.trimIndent().replace('¤', '$') })

    private fun Run.endpoint(method: String, path: String): Endpoint =
        records.filterIsInstance<Endpoint>().firstOrNull { it.method == method && it.path == path }
            ?: fail("Sin ruta $method $path: ${records.filterIsInstance<Endpoint>().map { "${it.kind} ${it.method} ${it.path} (${it.framework})" }}")

    private fun Run.http(source: String): List<Relation> = relations.filter { it.source == source && it.kind == RelationKind.HTTP }

    private fun Run.entity(name: String): DataEntity = records.filterIsInstance<DataEntity>().firstOrNull { it.name == name } ?: fail("Sin tabla $name: ${records.filterIsInstance<DataEntity>().map { it.name }}")

    /** Registros de las relaciones (no la copia del código para el visor, que se redacta aparte). */
    private fun Run.analysisRecords(): List<String> =
        records.filter { it is Relation || it is Endpoint || it is DataEntity || it is ConfigKey }.map { it.toString() }

    private fun Run.summary(): RelationsSummary = records.filterIsInstance<RelationsSummary>().single()

    @Test
    fun `JavaScript - Express, React, axios, Sequelize y configuracion`() {
        val run = project(
            "server/package.json" to """{"name": "server", "dependencies": {"express": "^4.19.0", "sequelize": "^6.0.0"}}""",
            "server/.env.example" to "DATABASE_URL=postgres://localhost/demo\nPORT=3000\n",
            "server/app.js" to """
                const express = require('express');
                const users = require('./routes/users');
                const app = express();
                app.use(express.json());
                app.use('/api/users', users);
                app.get('/health', (req, res) => res.send('ok'));
                app.listen(process.env.PORT);
            """,
            "server/routes/users.js" to """
                const router = require('express').Router();
                const ctrl = require('../controllers/users');
                router.get('/', ctrl.list);
                router.get('/:id', ctrl.get);
                router.post('/', ctrl.create);
                module.exports = router;
            """,
            "server/controllers/users.js" to """
                const User = require('../models/user');
                exports.list = async (req, res) => res.json(await User.findAll());
                exports.get = async (req, res) => res.json(await User.findByPk(req.params.id));
                exports.create = async (req, res) => res.json(await User.create(req.body));
            """,
            "server/models/user.js" to """
                const { DataTypes } = require('sequelize');
                const sequelize = require('../db');
                module.exports = sequelize.define('User', { name: DataTypes.STRING }, { tableName: 'users' });
            """,
            "server/db.js" to """
                const { Sequelize } = require('sequelize');
                module.exports = new Sequelize(process.env.DATABASE_URL);
            """,
            "client/package.json" to """{"name": "client", "dependencies": {"react": "^18.0.0", "react-router-dom": "^6.0.0", "axios": "^1.0.0"}}""",
            "client/src/api.js" to """
                import axios from 'axios';
                const api = axios.create({ baseURL: '/api' });
                export const listUsers = () => api.get('/users');
                export const createUser = (name, email) => api.post('/users', { name, email });
                export const getUser = (id) => fetch(`/api/users/¤{id}`).then(r => r.json());
                export const removeUser = (id) => api.delete(`/users/¤{id}`);
                export const stats = () => fetch('/api/stats');
                export const github = () => fetch('https://api.github.com/users/octocat?token=abc');
                // fetch('/api/comentario') no es una llamada
            """,
            "client/src/App.jsx" to """
                import { Routes, Route } from 'react-router-dom';
                import Users from './pages/Users';
                export default function App() {
                  return <Routes><Route path="/users" element={<Users />} /></Routes>;
                }
            """,
            "client/src/pages/Users.jsx" to """
                export default function Users() {
                  const url = import.meta.env.VITE_API_URL;
                  return <div>{url}</div>;
                }
            """,
        )
        assertFiveAttributes(run)
        val ctrl = "server/controllers/users.js"
        // Rutas con su prefijo de montaje y su controlador.
        assertEquals(ctrl, run.endpoint("GET", "/api/users").handlerFile)
        assertEquals(ctrl, run.endpoint("POST", "/api/users").handlerFile)
        assertEquals("server/routes/users.js", run.endpoint("GET", "/api/users/:id").file)
        assertEquals("server/app.js", run.endpoint("GET", "/health").file)
        val page = run.records.filterIsInstance<Endpoint>().single { it.kind == EndpointKind.PAGE }
        assertEquals("/users", page.path)
        assertEquals("client/src/pages/Users.jsx", page.handlerFile)
        assertEquals(VERIFIED, run.rel("server/app.js", "server/routes/users.js", RelationKind.REGISTRATION).confidence)
        val route = run.rel("server/routes/users.js", ctrl, RelationKind.ROUTE_HANDLER)
        assertTrue(route.exchanged.any { it.startsWith("GET /api/users → ctrl.list") }, "${route.exchanged}")
        assertTrue(route.impact.affected.any { it.startsWith("client/src/api.js:") }, "Cambiar la ruta rompe las llamadas del cliente: ${route.impact}")
        assertEquals(VERIFIED, run.rel("client/src/App.jsx", "client/src/pages/Users.jsx", RelationKind.ROUTE_HANDLER).confidence)

        // Frontend → backend: ruta literal idéntica, campos del cuerpo y evidencia en los dos lados.
        val calls = run.http("client/src/api.js")
        val toCtrl = calls.single { it.target == ctrl }
        assertEquals(PROBABLE, toCtrl.confidence)
        assertTrue("POST /api/users {name, email}" in toCtrl.exchanged, "${toCtrl.exchanged}")
        assertTrue(toCtrl.exchanged.any { it.startsWith("GET /api/users/{…}") }, "Parámetro de la ruta: ${toCtrl.exchanged}")
        assertTrue(toCtrl.evidence.any { it.path == "server/routes/users.js" }, "La evidencia señala la ruta del backend")
        assertTrue(toCtrl.evidence.any { it.detail.contains("con el método GET") && it.detail.contains("DELETE") }, "DELETE sin ruta DELETE: ${toCtrl.evidence.map { it.detail }}")
        val unmatched = run.external("client/src/api.js", "GET /api/stats")
        assertEquals(UNVERIFIED, unmatched.confidence)
        assertTrue(unmatched.evidence.single().detail.contains("ninguna ruta"))
        val github = run.external("client/src/api.js", "api.github.com")
        assertEquals(VERIFIED, github.confidence)
        assertTrue(github.reference.none { it == '?' }, "Sin la consulta (puede llevar claves): ${github.reference}")
        assertEquals(4, run.endpoint("GET", "/api/users").callerCount + run.endpoint("POST", "/api/users").callerCount + run.endpoint("GET", "/api/users/:id").callerCount + run.endpoint("GET", "/health").callerCount)

        // Datos y configuración.
        val db = run.rel(ctrl, "server/models/user.js", RelationKind.DB_ACCESS)
        assertTrue(db.exchanged.containsAll(listOf("tabla users: lectura", "tabla users: escritura")), "${db.exchanged}")
        assertEquals("server/models/user.js", run.entity("users").file)
        assertEquals(VERIFIED, run.rel("server/db.js", "server/.env.example", RelationKind.CONFIG_READ).confidence)
        assertEquals(UNVERIFIED, run.external("client/src/pages/Users.jsx", "variable de entorno VITE_API_URL").confidence)
        val key = run.records.filterIsInstance<ConfigKey>().single { it.name == "DATABASE_URL" }
        assertEquals(listOf("server/.env.example"), key.definedIn)
        assertTrue(run.analysisRecords().none { it.contains("postgres://") }, "Nunca el valor de una clave en las conexiones, rutas, datos ni configuración")

        val summary = run.summary()
        assertEquals(6, summary.httpCalls)
        assertEquals(4, summary.httpMatched)
        assertEquals(4, summary.endpoints)
        assertEquals(1, summary.pages)
    }

    @Test
    fun `Python - Django con DRF, FastAPI con Depends y Flask con blueprints`() {
        val run = project(
            "mysite/mysite/settings.py" to """
                import os
                DEBUG = True
                SECRET_KEY = os.environ.get('DJANGO_SECRET')
                ROOT_URLCONF = 'mysite.urls'
            """,
            "mysite/mysite/urls.py" to """
                from django.contrib import admin
                from django.urls import path, include
                from rest_framework.routers import DefaultRouter
                from blog.api import PostViewSet

                router = DefaultRouter()
                router.register(r'posts', PostViewSet)

                urlpatterns = [
                    path('admin/', admin.site.urls),
                    path('blog/', include('blog.urls')),
                    path('api/', include(router.urls)),
                ]
            """,
            "mysite/blog/__init__.py" to "",
            "mysite/blog/urls.py" to """
                from django.urls import path
                from . import views

                urlpatterns = [
                    path('', views.index),
                    path('<int:pk>/', views.detail, name='detail'),
                ]
            """,
            "mysite/blog/views.py" to """
                from django.conf import settings
                from .models import Post

                def index(request):
                    posts = Post.objects.filter(published=True)
                    return posts if settings.DEBUG else []

                def detail(request, pk):
                    return Post.objects.get(pk=pk)
            """,
            "mysite/blog/api.py" to """
                from rest_framework import viewsets
                from .models import Post

                class PostViewSet(viewsets.ModelViewSet):
                    queryset = Post.objects.all()
            """,
            "mysite/blog/models.py" to """
                from django.db import models

                class Post(models.Model):
                    title = models.CharField(max_length=200)
            """,
            "api/app/__init__.py" to "",
            "api/app/main.py" to """
                from fastapi import FastAPI
                from .routers import items

                app = FastAPI()
                app.include_router(items.router, prefix="/items")
            """,
            "api/app/routers/__init__.py" to "",
            "api/app/routers/items.py" to """
                from fastapi import APIRouter, Depends
                from ..database import get_db
                from ..models import Item

                router = APIRouter()

                @router.get("/")
                def list_items(db = Depends(get_db)):
                    return db.query(Item).all()

                @router.post("/{item_id}")
                def update_item(item_id: int):
                    return item_id
            """,
            "api/app/database.py" to """
                def get_db():
                    yield None
            """,
            "api/app/models.py" to """
                class Item(Base):
                    __tablename__ = "items"
            """,
            "web/app.py" to """
                from flask import Flask
                from .auth import bp

                app = Flask(__name__)
                app.register_blueprint(bp, url_prefix='/auth')
            """,
            "web/auth.py" to """
                from flask import Blueprint

                bp = Blueprint('auth', __name__)

                @bp.route('/login', methods=['GET', 'POST'])
                def login():
                    return 'ok'
            """,
            "scripts/client.py" to """
                import requests
                BASE = os.environ['BACKEND_URL']
                requests.post(f"{BASE}/auth/login", json={"user": "a", "password": "b"})
            """,
        )
        assertFiveAttributes(run)
        // Django: include() encadena prefijos y enlaza con las vistas.
        assertEquals("mysite/blog/views.py", run.endpoint("ANY", "/blog/<int:pk>/").handlerFile)
        assertEquals(VERIFIED, run.rel("mysite/mysite/urls.py", "mysite/blog/urls.py", RelationKind.REGISTRATION).confidence)
        assertTrue(run.rel("mysite/blog/urls.py", "mysite/blog/views.py", RelationKind.ROUTE_HANDLER).exchanged.any { it.contains("/blog/<int:pk>/") })
        assertEquals("mysite/blog/api.py", run.endpoint("ANY", "/api/posts/<pk>/").handlerFile)
        val orm = run.rel("mysite/blog/views.py", "mysite/blog/models.py", RelationKind.DB_ACCESS)
        assertEquals(VERIFIED, orm.confidence)
        assertTrue("tabla blog_post: lectura" in orm.exchanged, "${orm.exchanged}")
        assertEquals(VERIFIED, run.rel("mysite/blog/views.py", "mysite/mysite/settings.py", RelationKind.CONFIG_READ).confidence)
        assertEquals(UNVERIFIED, run.external("mysite/mysite/settings.py", "variable de entorno DJANGO_SECRET").confidence)
        // FastAPI: include_router con prefijo, Depends y SQLAlchemy.
        assertEquals("api/app/routers/items.py", run.endpoint("GET", "/items").file)
        assertEquals("api/app/routers/items.py", run.endpoint("POST", "/items/{item_id}").file)
        assertEquals(PROBABLE, run.rel("api/app/routers/items.py", "api/app/database.py", RelationKind.INJECTION).confidence)
        assertTrue("tabla items: lectura" in run.rel("api/app/routers/items.py", "api/app/models.py", RelationKind.DB_ACCESS).exchanged)
        // Flask: blueprint registrado con prefijo; un cliente con URL base variable solo coincide en parte.
        run.endpoint("POST", "/auth/login")
        val partial = run.rel("scripts/client.py", "web/auth.py", RelationKind.HTTP)
        assertEquals(UNVERIFIED, partial.confidence)
        assertTrue(partial.exchanged.single().contains("{user, password}"), "${partial.exchanged}")
    }

    @Test
    fun `JVM y Android - Spring, Retrofit, Hilt, Room, manifiesto, recursos e Intents`() {
        val base = "backend/src/main/java/com/demo/api"
        val app = "android/app/src/main/java/com/demo/app"
        val run = project(
            "backend/src/main/resources/application.properties" to """
                server.servlet.context-path=/api
                app.jwt.secret=supersecreto
                spring.datasource.url=jdbc:postgresql://¤{DB_HOST}:5432/demo
            """,
            "$base/UserController.java" to """
                package com.demo.api;

                import org.springframework.web.bind.annotation.*;
                import java.util.List;

                @RestController
                @RequestMapping("/users")
                public class UserController {
                    private final UserService service;

                    public UserController(UserService service) {
                        this.service = service;
                    }

                    @GetMapping
                    public List<User> all() { return service.all(); }

                    @GetMapping("/{id}")
                    public User one(@PathVariable Long id) { return null; }

                    @PostMapping
                    public User create(@RequestBody User user) { return user; }
                }
            """,
            "$base/UserService.java" to "package com.demo.api;\n\npublic interface UserService {\n    java.util.List<User> all();\n}\n",
            "$base/UserServiceImpl.java" to """
                package com.demo.api;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Service;

                @Service
                public class UserServiceImpl implements UserService {
                    private final UserRepository repo;

                    @Value("¤{app.jwt.secret}")
                    private String secret;

                    public UserServiceImpl(UserRepository repo) { this.repo = repo; }

                    public java.util.List<User> all() { return repo.findAll(); }
                }
            """,
            "$base/UserRepository.java" to "package com.demo.api;\n\nimport org.springframework.data.jpa.repository.JpaRepository;\n\npublic interface UserRepository extends JpaRepository<User, Long> {}\n",
            "$base/User.java" to """
                package com.demo.api;

                import jakarta.persistence.*;

                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                }
            """,
            "android/app/build.gradle.kts" to """
                android {
                    namespace = "com.demo.app"
                    defaultConfig {
                        buildConfigField("String", "API_URL", "\"http://10.0.2.2:8080/api/\"")
                    }
                }
            """,
            "android/app/src/main/AndroidManifest.xml" to """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:name=".App" android:label="@string/app_name">
                        <activity android:name=".MainActivity" />
                        <activity android:name=".DetailActivity" />
                        <activity android:name=".ui.Missing" />
                        <receiver android:name=".SyncReceiver">
                            <intent-filter>
                                <action android:name="com.demo.SYNC" />
                            </intent-filter>
                        </receiver>
                    </application>
                </manifest>
            """,
            "android/app/src/main/res/values/strings.xml" to """
                <resources>
                    <string name="app_name">Demo</string>
                    <string name="title">Título</string>
                    <string-array name="tabs"><item>A</item></string-array>
                </resources>
            """,
            "android/app/src/main/res/layout/activity_main.xml" to """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:text="@string/title" />
                    <com.demo.app.ui.ChartView android:id="@+id/chart" />
                </LinearLayout>
            """,
            "$app/App.kt" to "package com.demo.app\n\nclass App : android.app.Application()\n",
            "$app/MainActivity.kt" to """
                package com.demo.app

                import android.content.Intent
                import com.demo.app.databinding.ActivityMainBinding

                class MainActivity : androidx.appcompat.app.AppCompatActivity() {
                    fun open() {
                        setContentView(R.layout.activity_main)
                        val binding = ActivityMainBinding.inflate(layoutInflater)
                        title = getString(R.string.app_name)
                        val tabs = resources.getStringArray(R.array.tabs)
                        startActivity(Intent(this, DetailActivity::class.java))
                        sendBroadcast(Intent("com.demo.SYNC"))
                        val url = BuildConfig.API_URL
                        val missing = R.layout.no_existe
                    }
                }
            """,
            "$app/DetailActivity.kt" to "package com.demo.app\n\nclass DetailActivity : androidx.appcompat.app.AppCompatActivity()\n",
            "$app/SyncReceiver.kt" to "package com.demo.app\n\nclass SyncReceiver : android.content.BroadcastReceiver()\n",
            "$app/ui/ChartView.kt" to "package com.demo.app.ui\n\nclass ChartView : android.view.View(null)\n",
            "$app/net/ApiService.kt" to """
                package com.demo.app.net

                import retrofit2.Retrofit
                import retrofit2.http.GET
                import retrofit2.http.POST
                import retrofit2.http.Path

                interface ApiService {
                    @GET("users")
                    suspend fun users(): List<Any>

                    @GET("users/{id}")
                    suspend fun user(@Path("id") id: Long): Any

                    @POST("orders")
                    suspend fun order(): Any
                }

                val retrofit = Retrofit.Builder().baseUrl("http://10.0.2.2:8080/api/").build()
            """,
            "$app/data/Repo.kt" to "package com.demo.app.data\n\ninterface Repo\n",
            "$app/data/RepoImpl.kt" to "package com.demo.app.data\n\nclass RepoImpl : Repo\n",
            "$app/di/RepoModule.kt" to """
                package com.demo.app.di

                import com.demo.app.data.Repo
                import com.demo.app.data.RepoImpl
                import dagger.Binds

                abstract class RepoModule {
                    @Binds
                    abstract fun bindRepo(impl: RepoImpl): Repo
                }
            """,
            "$app/MainViewModel.kt" to """
                package com.demo.app

                import com.demo.app.data.Repo
                import javax.inject.Inject

                class MainViewModel @Inject constructor(private val repo: Repo)
            """,
            "$app/db/Note.kt" to """
                package com.demo.app.db

                import androidx.room.Entity

                @Entity(tableName = "notes")
                data class Note(val id: Long)
            """,
            "$app/db/NoteDao.kt" to """
                package com.demo.app.db

                import androidx.room.Dao
                import androidx.room.Insert
                import androidx.room.Query

                @Dao
                interface NoteDao {
                    @Query("SELECT * FROM notes WHERE id = :id")
                    fun one(id: Long): Note

                    @Insert
                    fun add(note: Note)
                }
            """,
        )
        assertFiveAttributes(run)
        // Spring: prefijo de la clase y context-path; inyección por la interfaz; Spring Data; @Value.
        assertEquals("$base/UserController.java", run.endpoint("GET", "/api/users/{id}").file)
        run.endpoint("POST", "/api/users")
        val inj = run.rel("$base/UserController.java", "$base/UserServiceImpl.java", RelationKind.INJECTION)
        assertEquals(PROBABLE, inj.confidence)
        assertEquals(listOf("UserService"), inj.exchanged)
        assertTrue("tabla users: acceso" in run.rel("$base/UserRepository.java", "$base/User.java", RelationKind.DB_ACCESS).exchanged)
        assertEquals(VERIFIED, run.rel("$base/UserServiceImpl.java", "backend/src/main/resources/application.properties", RelationKind.CONFIG_READ).confidence)
        assertEquals(UNVERIFIED, run.external("backend/src/main/resources/application.properties", "variable de entorno DB_HOST").confidence)
        assertTrue(run.analysisRecords().none { it.contains("supersecreto") }, "Nunca el valor de una clave")
        // Retrofit con su URL base (emulador → máquina local) contra Spring.
        val retro = run.http("$app/net/ApiService.kt")
        assertEquals(PROBABLE, retro.single { it.target == "$base/UserController.java" }.confidence)
        assertEquals("POST /api/orders", retro.single { it.external != null }.external)
        // Android: manifiesto (con su namespace de Gradle), recursos, ViewBinding, Intents y broadcasts.
        val manifest = "android/app/src/main/AndroidManifest.xml"
        assertEquals(VERIFIED, run.rel(manifest, "$app/MainActivity.kt", RelationKind.REGISTRATION).confidence)
        assertEquals(VERIFIED, run.rel(manifest, "$app/App.kt", RelationKind.REGISTRATION).confidence)
        assertEquals(BROKEN, run.broken(manifest, ".ui.Missing").confidence)
        val strings = run.rel("$app/MainActivity.kt", "android/app/src/main/res/values/strings.xml", RelationKind.RESOURCE)
        assertTrue(strings.exchanged.containsAll(listOf("R.string.app_name", "R.array.tabs")), "${strings.exchanged}")
        assertTrue(run.rel("$app/MainActivity.kt", "android/app/src/main/res/layout/activity_main.xml", RelationKind.RESOURCE).exchanged.contains("ActivityMainBinding"))
        assertEquals(UNVERIFIED, run.external("$app/MainActivity.kt", "recurso R.layout.no_existe").confidence)
        assertEquals(VERIFIED, run.rel("android/app/src/main/res/layout/activity_main.xml", "$app/ui/ChartView.kt", RelationKind.REFERENCE).confidence)
        assertEquals(VERIFIED, run.rel("$app/MainActivity.kt", "$app/DetailActivity.kt", RelationKind.IPC).confidence)
        assertEquals(PROBABLE, run.rel("$app/MainActivity.kt", "$app/SyncReceiver.kt", RelationKind.IPC).confidence)
        assertEquals(VERIFIED, run.rel("$app/MainActivity.kt", "android/app/build.gradle.kts", RelationKind.CONFIG_READ).confidence)
        // Hilt: @Binds; Room: @Query y @Insert.
        assertEquals(PROBABLE, run.rel("$app/MainViewModel.kt", "$app/data/RepoImpl.kt", RelationKind.INJECTION).confidence)
        val dao = run.rel("$app/db/NoteDao.kt", "$app/db/Note.kt", RelationKind.DB_ACCESS)
        assertTrue(dao.exchanged.containsAll(listOf("tabla notes: lectura", "tabla notes: escritura")), "${dao.exchanged}")
        // Las actividades declaradas no son "sin referencias"; la que no se declara, sí.
        assertTrue(!run.graph("$app/DetailActivity.kt").unreferenced)
    }

    @Test
    fun `Laravel, ASP NET, Next js, NestJS, Unity, Godot, Electron, Flutter, Qt y XAML`() {
        val run = project(
            // Laravel
            "shop/routes/api.php" to """
                <?php
                use App\Http\Controllers\PostController;
                use Illuminate\Support\Facades\Route;

                Route::get('/posts', [PostController::class, 'index']);
                Route::post('/posts', [PostController::class, 'store']);
                Route::apiResource('comments', CommentController::class);
                Route::prefix('admin')->group(function () {
                    Route::get('/stats', [PostController::class, 'stats']);
                });
                Route::get('/broken', [PostController::class, 'missing']);
            """,
            "shop/app/Http/Controllers/PostController.php" to """
                <?php
                namespace App\Http\Controllers;

                use App\Models\Post;
                use Illuminate\Support\Facades\DB;

                class PostController extends Controller
                {
                    public function index() { return Post::where('published', true)->get(); }
                    public function store(Request ¤r) { return Post::create(¤r->all()); }
                    public function stats() { return DB::table('stats')->get(); }
                }
            """,
            "shop/app/Http/Controllers/CommentController.php" to """
                <?php
                namespace App\Http\Controllers;

                class CommentController extends Controller
                {
                    public function index() {}
                    public function store() {}
                    public function show() {}
                    public function update() {}
                    public function destroy() {}
                }
            """,
            "shop/app/Models/Post.php" to "<?php\nnamespace App\\Models;\n\nclass Post extends Model\n{\n}\n",
            "shop/config/app.php" to "<?php\nreturn [\n    'name' => env('APP_NAME', 'Tienda'),\n    'debug' => env('APP_DEBUG', false),\n];\n",
            "shop/.env" to "APP_NAME=Tienda\nAPP_DEBUG=true\n",
            "shop/resources/js/app.js" to "import axios from 'axios';\naxios.get('/api/posts');\naxios.delete('/api/comments/' + id);\n",
            // ASP.NET Core
            "Api/Program.cs" to """
                var builder = WebApplication.CreateBuilder(args);
                builder.Services.AddScoped<IOrderService, OrderService>();
                var conn = builder.Configuration.GetConnectionString("Default");
                var key = builder.Configuration["Jwt:Key"];
                var app = builder.Build();
                app.MapGet("/health", () => "ok");
                var v2 = app.MapGroup("/v2");
                v2.MapGet("/ping", () => "pong");
                app.Run();
            """,
            "Api/appsettings.json" to """{"ConnectionStrings": {"Default": "Server=x"}, "Jwt": {"Key": "k", "Issuer": "i"}, "Hosts": ["a", "b"]}""",
            "Api/Controllers/OrdersController.cs" to """
                namespace Api.Controllers;

                [ApiController]
                [Route("api/[controller]")]
                public class OrdersController : ControllerBase
                {
                    private readonly IOrderService _svc;
                    public OrdersController(IOrderService svc) { _svc = svc; }

                    [HttpGet]
                    public IActionResult Get() => Ok(_svc.All());

                    [HttpGet("{id}")]
                    public IActionResult GetOne(int id) => Ok();
                }
            """,
            "Api/Services/IOrderService.cs" to "namespace Api.Services;\n\npublic interface IOrderService { }\n",
            "Api/Services/OrderService.cs" to """
                namespace Api.Services;

                public class OrderService : IOrderService
                {
                    private readonly AppDbContext _context;
                    public OrderService(AppDbContext context) { _context = context; }
                    public object All() => _context.Orders.Where(o => o.Paid).ToList();
                }
            """,
            "Api/Data/AppDbContext.cs" to "namespace Api.Data;\n\npublic class AppDbContext : DbContext\n{\n    public DbSet<Order> Orders { get; set; }\n}\n",
            // Next.js
            "web/package.json" to """{"name": "web", "dependencies": {"next": "14.0.0", "react": "18.2.0", "swr": "2.0.0"}}""",
            "web/pages/api/hello.ts" to "export default function handler(req, res) { res.json({}) }\n",
            "web/app/api/items/route.ts" to "export async function GET() { return Response.json([]) }\nexport async function POST() { return Response.json({}) }\n",
            "web/app/dashboard/page.tsx" to "export default function Page() { return null }\n",
            "web/components/List.tsx" to """
                import useSWR from 'swr';
                export function List() {
                  const { data } = useSWR('/api/items');
                  fetch('/api/hello', { method: 'POST', body: JSON.stringify({ a: 1 }) });
                  return null;
                }
            """,
            // NestJS
            "nest/package.json" to """{"name": "nest", "dependencies": {"@nestjs/core": "10.0.0"}}""",
            "nest/src/main.ts" to "import { NestFactory } from '@nestjs/core';\nasync function bootstrap() {\n  const app = await NestFactory.create(AppModule);\n  app.setGlobalPrefix('v1');\n}\n",
            "nest/src/cats/cats.controller.ts" to """
                import { Controller, Get } from '@nestjs/common';
                import { CatsService } from './cats.service';

                @Controller('cats')
                export class CatsController {
                  constructor(private readonly catsService: CatsService) {}

                  @Get()
                  findAll() { return []; }

                  @Get(':id')
                  findOne() { return null; }
                }
            """,
            "nest/src/cats/cats.service.ts" to "import { Injectable } from '@nestjs/common';\n\n@Injectable()\nexport class CatsService {}\n",
            // Unity
            "Game/Assets/Scripts/Player.cs" to """
                using UnityEngine;
                public class Player : MonoBehaviour {
                    void Start() {
                        var enemy = Resources.Load<GameObject>("Prefabs/Enemy");
                        UnityEngine.SceneManagement.SceneManager.LoadScene("Main");
                    }
                }
            """,
            "Game/Assets/Scripts/Player.cs.meta" to "fileFormatVersion: 2\nguid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n",
            "Game/Assets/Scenes/Main.unity" to """
                %YAML 1.1
                --- !u!114 &1
                MonoBehaviour:
                  m_Script: {fileID: 11500000, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 3}
                --- !u!114 &2
                MonoBehaviour:
                  m_Script: {fileID: 11500000, guid: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, type: 3}
            """,
            "Game/Assets/Scenes/Main.unity.meta" to "fileFormatVersion: 2\nguid: cccccccccccccccccccccccccccccccc\n",
            "Game/Assets/Resources/Prefabs/Enemy.prefab" to "%YAML 1.1\n--- !u!1 &1\nGameObject:\n  m_Name: Enemy\n",
            "Game/ProjectSettings/EditorBuildSettings.asset" to """
                EditorBuildSettings:
                  m_Scenes:
                  - enabled: 1
                    path: Assets/Scenes/Main.unity
                    guid: cccccccccccccccccccccccccccccccc
            """,
            // Godot
            "godot/project.godot" to "[application]\nrun/main_scene=\"res://main.tscn\"\n\n[autoload]\nGlobal=\"*res://global.gd\"\n",
            "godot/main.tscn" to "[gd_scene load_steps=3 format=3]\n\n[ext_resource type=\"Script\" path=\"res://player.gd\" id=\"1\"]\n[ext_resource type=\"PackedScene\" path=\"res://missing.tscn\" id=\"2\"]\n",
            "godot/player.gd" to "extends CharacterBody2D\n",
            "godot/global.gd" to "extends Node\n",
            // Electron
            "desktop/main.js" to "const { ipcMain } = require('electron');\nipcMain.handle('save-file', async () => true);\nwin.webContents.send('progress', 1);\n",
            "desktop/preload.js" to "const { contextBridge, ipcRenderer } = require('electron');\ncontextBridge.exposeInMainWorld('api', {\n  save: (d) => ipcRenderer.invoke('save-file', d),\n  onProgress: (cb) => ipcRenderer.on('progress', cb),\n  orphan: () => ipcRenderer.send('nadie-escucha'),\n});\n",
            "desktop/renderer.js" to "window.api.save({ a: 1 });\n",
            // Flutter + Android nativo
            "mobile/lib/battery.dart" to "import 'package:flutter/services.dart';\n\nconst platform = MethodChannel('com.demo/battery');\n",
            "mobile/android/app/src/main/kotlin/com/demo/MainActivity.kt" to "package com.demo\n\nclass MainActivity : FlutterActivity() {\n    fun configure() {\n        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, \"com.demo/battery\")\n    }\n}\n",
            // Qt + CMake
            "qt/CMakeLists.txt" to "set(SRC main.cpp mainwindow.cpp)\nadd_executable(app ¤{SRC} mainwindow.ui missing.cpp)\n",
            "qt/main.cpp" to "#include \"ui_mainwindow.h\"\nint main() { return 0; }\n",
            "qt/mainwindow.cpp" to "int x = 1;\n",
            "qt/mainwindow.ui" to "<ui version=\"4.0\"></ui>\n",
            // WPF
            "wpf/MainWindow.xaml" to "<Window x:Class=\"Wpf.MainWindow\" xmlns:x=\"http://schemas.microsoft.com/winfx/2006/xaml\"></Window>\n",
            "wpf/MainWindow.xaml.cs" to "namespace Wpf\n{\n    public partial class MainWindow : Window { }\n}\n",
        )
        assertFiveAttributes(run)
        // Laravel
        assertEquals("shop/app/Http/Controllers/PostController.php", run.endpoint("GET", "/api/posts").handlerFile)
        run.endpoint("GET", "/api/admin/stats")
        assertEquals("shop/app/Http/Controllers/CommentController.php", run.endpoint("DELETE", "/api/comments/{comment}").handlerFile)
        assertTrue(run.broken("shop/routes/api.php", "PostController@missing").evidence.single().detail.contains("no declara el método «missing»"))
        assertTrue("tabla posts: lectura" in run.rel("shop/app/Http/Controllers/PostController.php", "shop/app/Models/Post.php", RelationKind.DB_ACCESS).exchanged)
        assertEquals(UNVERIFIED, run.external("shop/app/Http/Controllers/PostController.php", "tabla stats").confidence)
        assertEquals(VERIFIED, run.rel("shop/config/app.php", "shop/.env", RelationKind.CONFIG_READ).confidence)
        val js = run.http("shop/resources/js/app.js")
        assertTrue(js.any { it.target == "shop/app/Http/Controllers/PostController.php" && it.confidence == PROBABLE })
        assertTrue(js.any { it.target == "shop/app/Http/Controllers/CommentController.php" }, "${js.map { it.target ?: it.external }}")
        // ASP.NET Core
        assertEquals("Api/Controllers/OrdersController.cs", run.endpoint("GET", "/api/Orders/{id}").file)
        run.endpoint("GET", "/v2/ping")
        val reg = run.rel("Api/Controllers/OrdersController.cs", "Api/Services/OrderService.cs", RelationKind.INJECTION)
        assertTrue(reg.evidence.first().detail.contains("Program.cs"), reg.evidence.first().detail)
        assertTrue("tabla Orders: lectura" in run.rel("Api/Services/OrderService.cs", "Api/Data/AppDbContext.cs", RelationKind.DB_ACCESS).exchanged)
        val cfg = run.rel("Api/Program.cs", "Api/appsettings.json", RelationKind.CONFIG_READ)
        assertTrue(cfg.exchanged.containsAll(listOf("ConnectionStrings:Default", "Jwt:Key")), "${cfg.exchanged}")
        // Next.js y NestJS
        run.endpoint("POST", "/api/items")
        assertEquals(EndpointKind.PAGE, run.endpoint("ANY", "/dashboard").kind)
        val next = run.http("web/components/List.tsx")
        assertEquals(setOf("web/app/api/items/route.ts", "web/pages/api/hello.ts"), next.mapNotNull { it.target }.toSet())
        assertEquals("nest/src/cats/cats.controller.ts", run.endpoint("GET", "/v1/cats/:id").file)
        assertEquals(PROBABLE, run.rel("nest/src/cats/cats.controller.ts", "nest/src/cats/cats.service.ts", RelationKind.INJECTION).confidence)
        // Unity
        assertEquals(VERIFIED, run.rel("Game/Assets/Scenes/Main.unity", "Game/Assets/Scripts/Player.cs", RelationKind.REFERENCE).confidence)
        assertEquals(VERIFIED, run.rel("Game/ProjectSettings/EditorBuildSettings.asset", "Game/Assets/Scenes/Main.unity", RelationKind.REGISTRATION).confidence)
        assertEquals(PROBABLE, run.rel("Game/Assets/Scripts/Player.cs", "Game/Assets/Resources/Prefabs/Enemy.prefab", RelationKind.RESOURCE).confidence)
        assertEquals(PROBABLE, run.rel("Game/Assets/Scripts/Player.cs", "Game/Assets/Scenes/Main.unity", RelationKind.RESOURCE).confidence)
        assertTrue(run.records.filterIsInstance<CoverageNote>().any { it.detail.contains("GUID") && it.detail.startsWith("1 ") }, "El GUID que falta se cuenta")
        // Godot
        assertEquals(VERIFIED, run.rel("godot/main.tscn", "godot/player.gd", RelationKind.REFERENCE).confidence)
        assertEquals(BROKEN, run.broken("godot/main.tscn", "res://missing.tscn").confidence)
        assertEquals(VERIFIED, run.rel("godot/project.godot", "godot/main.tscn", RelationKind.REGISTRATION).confidence)
        assertEquals(VERIFIED, run.rel("godot/project.godot", "godot/global.gd", RelationKind.REGISTRATION).confidence)
        // Electron
        assertEquals(PROBABLE, run.rel("desktop/preload.js", "desktop/main.js", RelationKind.IPC).confidence)
        assertEquals(PROBABLE, run.rel("desktop/main.js", "desktop/preload.js", RelationKind.IPC).confidence)
        assertEquals(PROBABLE, run.rel("desktop/renderer.js", "desktop/preload.js", RelationKind.IPC).confidence)
        assertTrue(run.relations.single { it.source == "desktop/preload.js" && it.external?.startsWith("canal nadie-escucha") == true }.evidence.single().detail.contains("nadie escucha"))
        // Flutter ↔ Android
        assertEquals(PROBABLE, run.rel("mobile/lib/battery.dart", "mobile/android/app/src/main/kotlin/com/demo/MainActivity.kt", RelationKind.IPC).confidence)
        // Qt y CMake
        assertEquals(VERIFIED, run.rel("qt/CMakeLists.txt", "qt/mainwindow.cpp", RelationKind.REGISTRATION).confidence)
        assertEquals(BROKEN, run.broken("qt/CMakeLists.txt", "missing.cpp").confidence)
        assertEquals(PROBABLE, run.rel("qt/main.cpp", "qt/mainwindow.ui").confidence)
        // WPF
        assertEquals(VERIFIED, run.rel("wpf/MainWindow.xaml", "wpf/MainWindow.xaml.cs", RelationKind.REFERENCE).confidence)
    }

    @Test
    fun `textos que parecen codigo no se convierten en conexiones`() {
        val run = project(
            "src/main/kotlin/demo/Docs.kt" to """
                package demo

                val ejemplo = "@GetMapping(\"/x\") @Entity class Falsa"
                val aviso = "Update available"
                val boton = "Delete from cart"
                val consulta = "select the best option from the list"
                // fetch('/api/comentado')
                fun f() = listOf("@Inject", "R.layout.nada")
            """,
            "README.md" to "Ejemplo: `app.get('/x', handler)` y `SELECT * FROM users`.\n",
            // Un proyecto de ejemplo dentro de una cadena de varias líneas (como en las pruebas de un analizador).
            "src/test/kotlin/demo/FixtureTest.kt" to "package demo\n\nval fixture = \"\"\"\n" +
                "const express = require('express');\nconst app = express();\napp.get('/api/x', (req, res) => res.send(process.env.SECRET));\n" +
                "fetch('/api/y');\n@GetMapping(\"/z\")\nfun z() = 1\nsetContentView(R.layout.main)\nval q = \"SELECT * FROM users WHERE id = 1\"\n\"\"\"\n" +
                "val real = \"¤{fixture.length}\"\n",
            "data/schema.json" to """{"createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (id INTEGER)"}""",
        )
        assertTrue(run.records.filterIsInstance<Endpoint>().isEmpty(), "${run.records.filterIsInstance<Endpoint>()}")
        assertTrue(run.records.filterIsInstance<ConfigKey>().isEmpty(), "${run.records.filterIsInstance<ConfigKey>()}")
        assertEquals(0, run.summary().httpCalls)
        assertTrue(run.records.filterIsInstance<DataEntity>().isEmpty(), "${run.records.filterIsInstance<DataEntity>()}")
        assertTrue(run.relations.none { it.kind !in setOf(RelationKind.IMPORT, RelationKind.REFERENCE, RelationKind.INHERITANCE, RelationKind.IMPLEMENTATION) }, "${run.relations}")
    }
}
