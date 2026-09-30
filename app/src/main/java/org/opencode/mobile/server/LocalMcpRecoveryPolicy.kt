package org.opencode.mobile.server

/**
 * Какой из локальных MCP-процессов задействован.
 *
 * [processName] - как процесс называется в логах и в списке «мёртв».
 * [feature] - что пользователь теряет, когда процесс мёртв. Это разные вещи: мёртвый
 * ynison отнимает музыку, а не память, и путать их в сообщении - это враньё, а не
 * округление.
 */
enum class LocalMcpTarget(
    val processName: String,
    val feature: String,
) {
    YNISON(processName = "ynison", feature = "музыка"),
    MEMORY(processName = "память", feature = "память"),
}

/**
 * Состояние одного процесса.
 *
 * [started] - «сервер был нужен», а не «сервер жив». Живой процесс повторно не
 * поднимаем, а умерший остаётся нужным навсегда: сбросить этот флаг на неудачной
 * попытке - значит ослепить восстановление (ровно так и случилось, см. [LocalMcpPolicy]).
 */
data class LocalMcpProc(
    val started: Boolean,
    val alive: Boolean,
    val recoverable: Boolean,
) {
    /** Нужен был, но процесса нет - самое интересное состояние. */
    val dead: Boolean
        get() = started && !alive
}

/** Всё, на чём решается судьба локальных MCP. */
data class LocalMcpState(
    val ynison: LocalMcpProc,
    val memory: LocalMcpProc,
    val recoveries: Int,
    val maxRecoveries: Int,
    val alreadyReported: Boolean,
) {
    fun proc(target: LocalMcpTarget): LocalMcpProc =
        when (target) {
            LocalMcpTarget.YNISON -> ynison
            LocalMcpTarget.MEMORY -> memory
        }
}

/** Что делать с локальными MCP в этой итерации цикла надзора. */
sealed class LocalMcpAction {
    /** Ничего не мертво - цикл просто ждёт. */
    data object Idle : LocalMcpAction()

    /**
     * Поднять заново. [attempt] - номер попытки, который записывает вызывающий: решение
     * не меняет состояние, только сообщает.
     */
    data class Retry(
        val target: LocalMcpTarget,
        val attempt: Int,
    ) : LocalMcpAction()

    /**
     * Процесс мёртв, но поднимать его нечем. Повтор бессмыслен, но молчать нельзя: раньше
     * это состояние проглатывалось циклом, и на экране оставалось «здоров» при мёртвой
     * музыке.
     */
    data class Unrecoverable(
        val targets: List<LocalMcpTarget>,
    ) : LocalMcpAction()

    /**
     * Попытки исчерпаны. [alreadyReported] - говорили ли мы об этом раньше: сообщение
     * должно прозвучать один раз, а не каждые три секунды.
     */
    data class GiveUp(
        val targets: List<LocalMcpTarget>,
        val alreadyReported: Boolean,
    ) : LocalMcpAction()
}

/**
 * Решение по локальным MCP. Чистая функция состояния: без Android и без процессов,
 * поэтому проверяется обычным JUnit-тестом.
 *
 * Раньше это решение жило внутри трёхсотстрочного цикла надзора вперемешку с
 * логированием, и проверить его было нечем - кроме как сломать телефон. А телефон
 * чинит себя сам: ensureYnisonScript перезаписывает битый скрипт из assets, recovery
 * успевает раньше, чем кто-то успевает занять порт. Три попытки так и не дали увидеть
 * ветку отказа.
 */
object LocalMcpPolicy {
    /** Порядок значим: при смерти обоих первым поднимается ynison. */
    private val TARGETS = listOf(LocalMcpTarget.YNISON, LocalMcpTarget.MEMORY)

    fun decide(state: LocalMcpState): LocalMcpAction {
        val dead = TARGETS.filter { state.proc(it).dead }
        val recoverable = dead.filter { state.proc(it).recoverable }
        val hopeless = dead.filterNot { state.proc(it).recoverable }
        val action =
            when {
                dead.isEmpty() -> LocalMcpAction.Idle
                // Поднять то, что поднять можно, даже если сосед мёртв безнадёжно:
                // вернуть к жизни одного из двух полезнее, чем доложить и замереть.
                // Сосед будет доложен, когда дойдёт черёд.
                state.recoveries < state.maxRecoveries && recoverable.isNotEmpty() ->
                    LocalMcpAction.Retry(recoverable.first(), state.recoveries + 1)

                hopeless.isNotEmpty() -> LocalMcpAction.Unrecoverable(hopeless)
                else -> LocalMcpAction.GiveUp(dead, state.alreadyReported)
            }
        return action
    }

    /**
     * Exit code ИМЕННО того процесса, который умер. Раньше код читался только у ynison,
     * поэтому при смерти одной только памяти в лог уезжал код живого процесса - улика
     * указывала не туда, и 137/OOM можно было вычеркнуть не глядя.
     */
    fun exitCodeOf(
        target: LocalMcpTarget,
        ynisonCode: Int?,
        memoryCode: Int?,
    ): Int? =
        when (target) {
            LocalMcpTarget.YNISON -> ynisonCode
            LocalMcpTarget.MEMORY -> memoryCode
        }

    /** Что именно недоступно - по фактически мёртвым, а не «и то и другое». */
    fun unavailableText(targets: List<LocalMcpTarget>): String = targets.joinToString(" и ") { it.feature }
}
