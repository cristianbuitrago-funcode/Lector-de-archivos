package com.funcodestudiocb.analyzer.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.SecretType
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
fun roleLabel(role: FileRole): String = stringResource(
    when (role) {
        FileRole.ENTRY_POINT -> R.string.role_entry_point
        FileRole.CONFIGURATION -> R.string.role_configuration
        FileRole.DEPENDENCIES -> R.string.role_dependencies
        FileRole.BUILD -> R.string.role_build
        FileRole.DATABASE -> R.string.role_database
        FileRole.AUTHENTICATION -> R.string.role_authentication
        FileRole.SERVER -> R.string.role_server
        FileRole.UI -> R.string.role_ui
        FileRole.SOURCE -> R.string.role_source
        FileRole.TEST -> R.string.role_test
        FileRole.DOCUMENTATION -> R.string.role_documentation
        FileRole.RESOURCE -> R.string.role_resource
        FileRole.MEDIA -> R.string.role_media
        FileRole.DATA -> R.string.role_data
        FileRole.BINARY -> R.string.role_binary
        FileRole.POSSIBLY_UNNECESSARY -> R.string.role_possibly_unnecessary
    },
)

fun roleColor(role: FileRole?): Color = when (role) {
    FileRole.ENTRY_POINT -> FuncodeColors.Gold
    FileRole.SERVER -> FuncodeColors.Electric
    FileRole.AUTHENTICATION -> FuncodeColors.High
    FileRole.DATABASE -> FuncodeColors.Medium
    FileRole.UI -> FuncodeColors.Low
    FileRole.TEST -> FuncodeColors.Success
    FileRole.CONFIGURATION, FileRole.DEPENDENCIES, FileRole.BUILD -> FuncodeColors.ElectricSoft
    FileRole.SOURCE -> FuncodeColors.TextPrimary
    FileRole.POSSIBLY_UNNECESSARY -> FuncodeColors.Critical
    else -> FuncodeColors.Info
}

@Composable
fun confidenceLabel(confidence: Confidence): String = stringResource(
    when (confidence) {
        Confidence.CONFIRMED -> R.string.confidence_confirmed
        Confidence.PROBABLE -> R.string.confidence_probable
        Confidence.POSSIBLE -> R.string.confidence_possible
    },
)

fun confidenceColor(confidence: Confidence): Color = when (confidence) {
    Confidence.CONFIRMED -> FuncodeColors.Success
    Confidence.PROBABLE -> FuncodeColors.Electric
    Confidence.POSSIBLE -> FuncodeColors.Medium
}

@Composable
fun entryKindLabel(kind: EntryPointKind): String = stringResource(
    when (kind) {
        EntryPointKind.ANDROID_LAUNCHER -> R.string.ep_android_launcher
        EntryPointKind.ANDROID_APPLICATION -> R.string.ep_android_application
        EntryPointKind.ANDROID_EXPORTED_COMPONENT -> R.string.ep_android_exported
        EntryPointKind.MAIN_FUNCTION -> R.string.ep_main
        EntryPointKind.SERVER_APP -> R.string.ep_server
        EntryPointKind.PACKAGE_MAIN -> R.string.ep_package_main
        EntryPointKind.PACKAGE_BIN -> R.string.ep_package_bin
        EntryPointKind.NPM_SCRIPT -> R.string.ep_npm_script
        EntryPointKind.WEB_PAGE -> R.string.ep_web_page
        EntryPointKind.WEB_BOOTSTRAP -> R.string.ep_web_bootstrap
        EntryPointKind.PHP_FRONT_CONTROLLER -> R.string.ep_php
        EntryPointKind.ELECTRON_MAIN -> R.string.ep_electron
        EntryPointKind.GAME_MAIN_SCENE -> R.string.ep_game_scene
        EntryPointKind.GAME_AUTOLOAD -> R.string.ep_game_autoload
    },
)

@Composable
fun ecosystemLabel(ecosystem: Ecosystem): String = stringResource(
    when (ecosystem) {
        Ecosystem.ANDROID -> R.string.eco_android
        Ecosystem.GRADLE -> R.string.eco_gradle
        Ecosystem.MAVEN -> R.string.eco_maven
        Ecosystem.NPM -> R.string.eco_npm
        Ecosystem.PYTHON -> R.string.eco_python
        Ecosystem.PHP_COMPOSER -> R.string.eco_php
        Ecosystem.DART_PUB -> R.string.eco_dart
        Ecosystem.DOTNET -> R.string.eco_dotnet
        Ecosystem.GO -> R.string.eco_go
        Ecosystem.RUST_CARGO -> R.string.eco_rust
        Ecosystem.RUBY -> R.string.eco_ruby
        Ecosystem.COCOAPODS -> R.string.eco_cocoapods
        Ecosystem.CMAKE -> R.string.eco_cmake
        Ecosystem.UNITY -> R.string.eco_unity
        Ecosystem.UNREAL -> R.string.eco_unreal
        Ecosystem.GODOT -> R.string.eco_godot
    },
)

@Composable
fun contentStateLabel(state: ContentState): String = stringResource(
    when (state) {
        ContentState.STORED -> R.string.content_stored
        ContentState.NOT_STORED_DISABLED -> R.string.content_disabled
        ContentState.NOT_STORED_LIMIT -> R.string.content_limit
        ContentState.NOT_STORED_GENERATED -> R.string.content_generated
        ContentState.TOO_LARGE -> R.string.content_too_large
        ContentState.BINARY -> R.string.content_binary
        ContentState.EMPTY -> R.string.content_empty
        ContentState.NOT_READ -> R.string.content_not_read
    },
)

@Composable
fun secretTypeLabel(type: SecretType): String = stringResource(
    when (type) {
        SecretType.PRIVATE_KEY -> R.string.secret_private_key
        SecretType.AWS_ACCESS_KEY -> R.string.secret_aws_access
        SecretType.AWS_SECRET_KEY -> R.string.secret_aws_secret
        SecretType.GITHUB_TOKEN -> R.string.secret_github
        SecretType.GITLAB_TOKEN -> R.string.secret_gitlab
        SecretType.GOOGLE_API_KEY -> R.string.secret_google_api
        SecretType.GOOGLE_OAUTH_SECRET -> R.string.secret_google_oauth
        SecretType.SLACK_TOKEN -> R.string.secret_slack_token
        SecretType.SLACK_WEBHOOK -> R.string.secret_slack_webhook
        SecretType.STRIPE_KEY -> R.string.secret_stripe
        SecretType.SENDGRID_KEY -> R.string.secret_sendgrid
        SecretType.OPENAI_KEY -> R.string.secret_openai
        SecretType.ANTHROPIC_KEY -> R.string.secret_anthropic
        SecretType.JWT -> R.string.secret_jwt
        SecretType.FIREBASE_SERVER_KEY -> R.string.secret_firebase
        SecretType.CONNECTION_STRING_PASSWORD -> R.string.secret_connection
        SecretType.PASSWORD -> R.string.secret_password
        SecretType.SECRET -> R.string.secret_generic
    },
)

@Composable
fun relationLabel(reason: RelationReason): String = stringResource(
    when (reason) {
        RelationReason.DUPLICATE -> R.string.related_duplicate
        RelationReason.DECLARED_BY -> R.string.related_declared_by
        RelationReason.UNITY_META -> R.string.related_unity_meta
    },
)

@Composable
fun categoryLabel(category: com.funcodestudiocb.analyzer.engine.api.TechnologyCategory): String = stringResource(
    when (category) {
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.LANGUAGE_RUNTIME -> R.string.tech_category_language_runtime
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.FRAMEWORK -> R.string.tech_category_framework
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.UI -> R.string.tech_category_ui
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.MOBILE -> R.string.tech_category_mobile
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.GAME_ENGINE -> R.string.tech_category_game_engine
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.DATABASE -> R.string.tech_category_database
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.DATA_ACCESS -> R.string.tech_category_data_access
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.EXTERNAL_SERVICE -> R.string.tech_category_external_service
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.CLOUD -> R.string.tech_category_cloud
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.AUTHENTICATION -> R.string.tech_category_authentication
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.NETWORKING -> R.string.tech_category_networking
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.TESTING -> R.string.tech_category_testing
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.BUILD_TOOL -> R.string.tech_category_build_tool
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.DEVOPS -> R.string.tech_category_devops
        com.funcodestudiocb.analyzer.engine.api.TechnologyCategory.LIBRARY -> R.string.tech_category_library
    },
)

@Composable
fun languageLevelLabel(level: Int): String = stringResource(
    when (level) {
        1 -> R.string.file_language_level_1
        2 -> R.string.file_language_level_2
        3 -> R.string.file_language_level_3
        else -> R.string.file_language_level_0
    },
)
