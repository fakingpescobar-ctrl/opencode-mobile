package org.opencode.mobile.server

import org.opencode.mobile.OpencodeApp

/**
 * Формализованные состояния runtime-слоя (Этап 2, RuntimeManager).
 *
 * В отличие от плоского [OpencodeServerService.ServerStatus] (STARTING/RUNNING/
 * ERROR/STOPPED), это полноценная стейт-машина с причинами остановки, кодом
 * последней ошибки и счётчиком рестартов — чтобы UI мог показать ЧТО именно
 * сломалось и стоит ли что-то делать (или это терминальный отказ runtime).
 */

/** Стадии жизненного цикла runtime-процесса. */
enum class RuntimeStage {
    /** Нет активного цикла (до первого старта / после остановки). */
    IDLE,

    /** Валидация нативного бинаря, musl-библиотек, memory.js, workspace. */
    PREPARING,

    /** Подъём локальной памяти MCP (MEMORY_PORT). */
    STARTING_MEMORY,

    /** Подъём opencode serve (PORT). */
    STARTING_SERVER,

    /** Сервер отвечает по HTTP и процесс жив. */
    HEALTHY,

    /** Работает, но деградация (например, память умерла, serve жив). */
    DEGRADED,

    /** Backoff-пауза между витками цикла рестартов (не краш — идёт retry). */
    RESTARTING,

    /** Виток не удался (краш/фейл старта) — следующий виток возможен. */
    CRASHED,

    /** Терминальный отказ: рестарты бессмысленны (невалидный runtime / лимит). */
    FAILED_PERMANENTLY,

    /** Останавливаем процессы по запросу пользователя. */
    STOPPING,

    /** Полностью остановлен. */
    STOPPED,
}

/** Машинно-читаемый код ошибки для UI/диагностики (не строка в уведомлении). */
enum class RuntimeErrorCode {
    /** libopencode.so / libldmusl.so нет в nativeLibraryDir. Терминально. */
    MISSING_NATIVE_BIN,

    /** ensureMuslLibs не смог материализовать libc/libstdc++/libgcc. Терминально. */
    MISSING_MUSL_LIBRARY,

    /** memory.js не удалось записать/обновить в filesDir. Терминально для памяти. */
    MEMORY_SCRIPT_FAILED,

    /** Процесс локальной памяти не стартовал. */
    MEMORY_START_FAILED,

    /** Процесс локальной памяти умер ПОСЛЕ успешного старта (serve жив, чат без неё). */
    MEMORY_DIED,

    /** Процесс serve не стартовал (startServe == null). */
    SERVER_START_FAILED,

    /** Сервер не ответил по HTTP за окно ожидания (30s). */
    HEALTH_TIMEOUT,

    /** Процесс serve умер сам (exit code != 0). */
    SERVER_CRASHED,

    /** Не классифицировано. */
    UNKNOWN,
}

/** Структурированная ошибка — приходит в UI вместо строкового статуса. */
data class RuntimeError(
    val stage: RuntimeStage,
    val code: RuntimeErrorCode,
    val message: String,
    /** false = терминальный отказ (рестарты бессмысленны), true = можно перезапустить. */
    val recoverable: Boolean,
)

/** Причина, по которой runtime перестал работать/был остановлен. */
enum class StopReason {
    /** Пользователь нажал Stop (ACTION_STOP). */
    USER_STOP,

    /** Процесс упал сам. */
    CRASH,

    /** Невалидный runtime (бинарь/libs отсутствуют) — старт невозможен. */
    INVALID_RUNTIME,

    /** Сервер не поднялся по HTTP. */
    HEALTH_TIMEOUT,

    /** Превышен лимит рестартов в цикле. */
    RESTART_LIMIT,

    /** Не классифицировано. */
    UNKNOWN,
}

/** Полное состояние runtime-цикла, публикуемое через StateFlow. */
data class RuntimeState(
    val stage: RuntimeStage = RuntimeStage.IDLE,
    val port: Int = OpencodeApp.ServerConfig.PORT,
    val memoryPort: Int = OpencodeRuntime.MEMORY_PORT,
    val workspaceExternal: Boolean = false,
    val lastError: RuntimeError? = null,
    val stopReason: StopReason? = null,
    /** Сколько рестартов сделал цикл с момента последнего HEALTHY. */
    val restartCount: Int = 0,
) {
    val isHealthy: Boolean get() = stage == RuntimeStage.HEALTHY
}
