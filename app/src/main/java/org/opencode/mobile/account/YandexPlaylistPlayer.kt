package org.opencode.mobile.account

import android.content.Context
import android.content.Intent
import android.util.Log
import org.opencode.mobile.media.MediaControlController
import org.opencode.mobile.media.MediaPlaybackSnapshot
import org.opencode.mobile.media.MediaUiAction
import org.opencode.mobile.media.MediaUiAutomation
import org.opencode.mobile.media.MediaUiNodeMatcher
import org.opencode.mobile.media.MediaUiOutcome
import org.opencode.mobile.media.MediaUiTarget

/**
 * Что получилось после попытки включить плейлист.
 *
 * [started] означает ровно одно: сессия Яндекс Музыки играет трек из этого плейлиста. Не
 * «экран открылся» и не «кнопку нажали» — оба этих факта ничего не говорят агенту, который
 * сейчас сообщит пользователю, что музыка пошла.
 */
data class PlaylistPlayback(
    val kind: Int,
    val title: String,
    val started: Boolean,
    val nowPlaying: String?,
    val nowPlayingArtist: String?,
    val message: String,
)

/**
 * Трек из начала плейлиста вместе с его настоящей позицией в плейлисте.
 *
 * Позиция, а не номер в списке проверки: неразобранные треки из головы выпадают, и индекс в
 * оставшемся списке после этого на единицу-другую меньше реального места в плейлисте.
 */
data class HeadTrack(
    val title: String,
    val position: Int,
)

/**
 * Запуск плейлиста в приложении Яндекс Музыки.
 *
 * У Яндекса нет API для воспроизведения: ни одной команды «поставь плейлист в очередь»
 * в их API нет, а `MediaCommand.PLAY` умеет лишь продолжить то, что уже играет, — то есть
 * продолжить чужой трек. Поэтому единственная честная дорога — открыть экран плейлиста
 * глубокой ссылкой и нажать там кнопку.
 *
 * Ссылка проверена на живом аккаунте: `yandexmusic://users/<login>/playlists/<kind>`.
 * Именно `users` во множественном числе, и именно `kind` числом: вариант с `uuid` или с
 * единственным `user` приложение не открывает, а `yandexmusic://playlist/...` — просто
 * игнорирует.
 */
// Объект упёрся в лимит detekt: тап, ожидание экрана и сборка ответа держатся рядом, потому
// что делить их между файлами значит прятать протокол запуска плейлиста от читателя.
// Если появится третий сценарий (например, shuffle или следующий трек) - пора выносить
// протокол в отдельный класс.
@Suppress("TooManyFunctions")
object YandexPlaylistPlayer {
    private const val TAG = "YandexPlaylistPlayer"
    const val PACKAGE = "ru.yandex.music"

    /**
     * Пауза после открытия ссылки, пока экран плейлиста ещё не нарисован.
     *
     * Число не выдумано: холодный старт приложения плюс загрузка плейлиста на проверенном
     * устройке укладывался в 12-13 секунд, отсюда запас. Ожидание нельзя убрать в ноль —
     * в первые секунды на экране ещё висит мини-плеер, у которого подпись «Слушать» тоже
     * есть, и тап ушёл бы в него вместо плейлиста.
     */
    private const val SETTLE_MS = 9_000L

    /** Сколько ждать появления кнопки после паузы; заодно переживает медленную отрисовку. */
    private const val UI_TIMEOUT_SECONDS = 15L
    private const val UI_TIMEOUT_MS = UI_TIMEOUT_SECONDS * 1000

    /** Сколько ждать, пока сессия опубликует новый трек после тапа. */
    private const val AFTER_TAP_MS = 3_000L

    /**
     * Две попытки, а не одна: если первый тап ушёл в ещё не догруженный экран, второй
     * почти наверняка попадёт. Больше двух бессмысленно — если и вторая не дала трек из
     * плейлиста, дело не в гонке, а в чём-то ещё, и врать дальше вредно.
     */
    private const val ATTEMPTS = 2

    /** Запас на чтение сессии, которое делается после каждого тапа. */
    private const val SESSION_READ_MS = 1_000L

    /**
     * Сколько запуск может идти в худшем случае, в миллисекундах.
     *
     * Считается из тех же констант, что и сам запуск, чтобы мост не держал свой потолок
     * отдельно: как только тайминги поменяются, число уедет само, а не разъедется с кодом.
     */
    const val WORST_CASE_MS = ATTEMPTS * (SETTLE_MS + UI_TIMEOUT_MS + AFTER_TAP_MS + SESSION_READ_MS)

    private var context: Context? = null

    @Synchronized
    fun initialize(appContext: Context) {
        if (context == null) context = appContext.applicationContext
    }

    /**
     * Открывает плейлист и запускает его очередь.
     *
     * [headTitles] — названия первых треков плейлиста по данным API, по порядку. Это и есть
     * проверка: мы сравниваем не «что-то играет», а «играет ли то, что должно». Сверка ловит
     * ровно тот случай, ради которого всё затевалось, — тап в мини-плеер, где продолжается
     * чужой трек.
     */
    // Четыре выхода - это четыре гварда перед дорогим действием: сервис выключен, нечем
    // подтверждать запуск, плеер не инициализирован, и только потом сам запуск. Свернуть их
    // в одну проверку означало бы или открывать экран заведомо безнадёжно, или прятать условия.
    @Suppress("ReturnCount")
    fun play(
        login: String,
        kind: Int,
        title: String,
        headTracks: List<HeadTrack>,
    ): PlaylistPlayback {
        // Проверяем сервис до того, как открываем экран: без него всё равно нечего нажимать,
        // а пользователь за полторы минуты смотрел бы на плейлист, который не играет.
        if (!MediaUiAutomation.isEnabled()) {
            Log.w(TAG, "refusing to play: ${MediaUiAutomation.REASON_NOT_ENABLED}")
            return failure(kind, title, "OpenCode accessibility service is off")
        }
        // Подтверждать запуск нечем, поэтому открывать экран и жать бессмысленно: мы бы честно
        // сказали «не в плейлисте» про пустой плейлист. Лучше сразу сказать правду.
        if (headTracks.isEmpty()) {
            Log.w(TAG, "refusing to play kind $kind: no playable tracks to confirm against")
            return failure(kind, title, "the playlist has no playable tracks to confirm a start")
        }
        val app = context
        if (app == null) {
            Log.w(TAG, "refusing to play kind $kind: player was never initialised")
            return failure(kind, title, "playlist playback is not initialised on this device")
        }
        val link = deepLink(login, kind)
        Log.i(TAG, "opening playlist $kind as $link")
        openPlaylist(app, link)
        // finally, а не после when: окно-релей держит чужую задачу в фокусе, и забытый
        // release оставил бы поверх Яндекс Музыки невидимую Activity на минуту.
        val outcome =
            try {
                startAndWatch(headTracks)
            } finally {
                YandexPlaylistLaunchActivity.release()
            }
        return when (outcome) {
            is Watch.Playing -> started(kind, title, outcome.snapshot, outcome.track)
            is Watch.Retry -> failure(kind, title, outcome.reason, outcome.observed)
            is Watch.Failed -> failure(kind, title, outcome.reason, outcome.observed)
        }
    }

    /**
     * Открытый плейлист доводится до состояния «играет трек из его начала».
     *
     * Отдельная функция, которая возвращает исход, а не пишет в return прямо в цикле: так
     * видно, что попытка бывает трёх сортов, и ни одна ветка не теряется по дороге.
     */
    // Три выхода - это три разных исхода попытки, и каждый виден сразу на своём месте.
    @Suppress("ReturnCount")
    private fun startAndWatch(headTracks: List<HeadTrack>): Watch {
        var last: Watch = Watch.Retry("the playlist did not start playing")
        for (attempt in 1..ATTEMPTS) {
            sleep(SETTLE_MS)
            // Сначала смотрим в сессию, и только потом решаем, жать ли вообще. Иначе повторный
            // тап при уже играющем плейлисте нажимает «Слушать» у отдельного трека и подменяет
            // очередь плейлиста одним треком - то есть ломает ровно то, что мы проверяем.
            confirmedOrFailed(headTracks)?.let { return it }
            last = attemptOnce(headTracks, attempt)
            if (last !is Watch.Retry) return last
        }
        return last
    }

    /**
     * Уже играет трек из начала плейлиста - успех, тапать больше не нужно.
     *
     * `null` означает «подтверждения нет, продолжаем», а не «плохо»: на первой попытке сессия
     * ещё пустая, и это нормально.
     */
    private fun confirmedOrFailed(headTracks: List<HeadTrack>): Watch? =
        listen(headTracks)?.let { Watch.Playing(it.snapshot, it.track) }
            ?: if (Thread.currentThread().isInterrupted) Watch.Failed("cancelled") else null

    /**
     * Текущее состояние сессии Яндекс Музыки, если в ней играет трек из начала плейлиста.
     *
     * Проверяются и название, и признак playing: сессия может показать заголовок трека в
     * состоянии PAUSED, и объявлять это запуском плейлиста было бы враньём.
     */
    @Suppress("ReturnCount")
    private fun listen(headTracks: List<HeadTrack>): Listening? {
        val playback = MediaControlController.status(PACKAGE).playback
        val track = startedTrack(playback.title, headTracks)
        if (track == null) return null
        if (!playback.isPlaying) {
            Log.w(TAG, "session shows ${playback.title} but is not playing: ${playback.state}")
            return null
        }
        return Listening(playback, track)
    }

    /** Сессия, в которой играет трек из начала плейлиста. */
    private data class Listening(
        val snapshot: MediaPlaybackSnapshot,
        val track: HeadTrack,
    )

    /**
     * Одна попытка: убедиться, что открыт нужный экран, нажать Play и подтвердить результат.
     *
     * Цепочкой из трёх шагов, а не тремя return-ами: видно весь порядок целиком, и у каждого
     * шага ровно один исход.
     */
    private fun attemptOnce(
        headTracks: List<HeadTrack>,
        attempt: Int,
    ): Watch =
        awaitPlaylistScreen(headTracks).asFailure()
            ?: tapFirstHeadTrack(headTracks).asFailure()
            ?: confirm(headTracks, attempt)

    /** Ошибка тапа - это и есть исход попытки; успешный тап продолжает работу. */
    private fun Tap.asFailure(): Watch? =
        when (this) {
            is Tap.Retry -> Watch.Retry(reason)
            is Tap.Fatal -> Watch.Failed(reason)
            Tap.Done -> null
        }

    /** Что показала сессия после тапа: играет трек из начала плейлиста или нет. */
    private fun confirm(
        headTracks: List<HeadTrack>,
        attempt: Int,
    ): Watch {
        sleep(AFTER_TAP_MS)
        val playing = listen(headTracks)
        if (playing != null) return Watch.Playing(playing.snapshot, playing.track)
        val heard = MediaControlController.status(PACKAGE).playback
        Log.w(TAG, "attempt $attempt of $ATTEMPTS: session still shows ${heard.title ?: "nothing"}")
        return Watch.Retry(
            "tapped Play but the session shows ${heard.title ?: "nothing"}, " +
                "which is not in this playlist",
            heard,
        )
    }

    /**
     * Ждём экран именно этого плейлиста, ничего на нём не нажимая.
     *
     * Ищем в дереве доступности любой из треков начала плейлиста: на экране плейлиста они
     * видны, на главном экране Яндекс Музыки - нет. Это дешёвый и однозначный признак, что
     * deep link привёл куда надо, а не просто открыл приложение.
     */
    private fun awaitPlaylistScreen(headTracks: List<HeadTrack>): Tap {
        val outcome =
            MediaUiAutomation.perform(
                target =
                    MediaUiTarget(
                        packageName = PACKAGE,
                        textContains = headTracks.map { it.title },
                        requireClickable = false,
                    ),
                action = MediaUiAction.ReadText,
                timeoutMs = UI_TIMEOUT_MS,
            )
        return when (outcome) {
            is MediaUiOutcome.Performed -> Tap.Done
            is MediaUiOutcome.NotFound ->
                Tap.Retry(
                    "the playlist screen never showed this playlist's tracks within " +
                        "${UI_TIMEOUT_SECONDS}s; Yandex Music opened something else",
                )
            is MediaUiOutcome.Rejected ->
                Tap.Retry("the playlist screen was found but not readable: ${outcome.reason}")
            is MediaUiOutcome.Failed ->
                Tap.Fatal("the OpenCode accessibility service is not usable: ${outcome.reason}")
        }
    }

    /** Итог наблюдения за плейлистом: играет свой трек, не успел или не смог. */
    private sealed interface Watch {
        data class Playing(
            val snapshot: MediaPlaybackSnapshot,
            val track: HeadTrack,
        ) : Watch

        data class Retry(
            val reason: String,
            val observed: MediaPlaybackSnapshot? = null,
        ) : Watch

        data class Failed(
            val reason: String,
            val observed: MediaPlaybackSnapshot? = null,
        ) : Watch
    }

    private fun started(
        kind: Int,
        title: String,
        snapshot: MediaPlaybackSnapshot,
        track: HeadTrack,
    ): PlaylistPlayback =
        PlaylistPlayback(
            kind = kind,
            title = title,
            started = true,
            nowPlaying = snapshot.title,
            nowPlayingArtist = snapshot.artist,
            message =
                if (track.position == 1) {
                    "the playlist \"$title\" is playing from its first track"
                } else {
                    "the playlist \"$title\" is playing, but it started at track ${track.position}: " +
                        "earlier tracks are not available for playback"
                },
        )

    /**
     * Неудача, которой всё же полезно сказать, что играет.
     *
     * `started=false` и «сейчас в сессии X» не противоречат друг другу: первое - про наш запуск,
     * второе - про Яндекс Музыку. Молчащий `now_playing` при неудачном запуске вводил в
     * заблуждение сильнее, чем помогал: агент видел пустоту и думал, что музыка вообще не играет.
     */
    private fun failure(
        kind: Int,
        title: String,
        reason: String,
        observed: MediaPlaybackSnapshot? = null,
    ): PlaylistPlayback =
        PlaylistPlayback(
            kind = kind,
            title = title,
            started = false,
            nowPlaying = observed?.title,
            nowPlayingArtist = observed?.artist,
            message = reason,
        )

    /** Глубокая ссылка открывает нужный экран сама, поэтому запускать активность вручную не надо. */
    private fun deepLink(
        login: String,
        kind: Int,
    ): String = "yandexmusic://users/$login/playlists/$kind"

    /**
     * Открыть плейлист по ссылке.
     *
     * Ссылку отправляет не наш контекст, а прозрачная [YandexPlaylistLaunchActivity]: у bridge
     * нет видимого окна, и Android в таком случае создаёт целевую Activity, но не выводит её
     * задачу наверх - ссылка выглядит доставленной, а плейлист не открывается.
     */
    private fun openPlaylist(
        app: Context,
        link: String,
    ) {
        app.startActivity(
            Intent(app, YandexPlaylistLaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(YandexPlaylistLaunchActivity.EXTRA_LINK, link),
        )
    }

    /**
     * Тап по строке первого трека плейлиста, а не по круглой кнопке «Слушать».
     *
     * Кнопка в шапке запускает очередь плейлиста, но продолжает её с сохранённой позиции: у
     * D.N.B это трек №10, и проверка головы честно отказывается считать это запуском плейлиста.
     * Строка трека начинает очередь с начала - ровно то, о чём просил инструмент.
     *
     * `preferTopmost` обязателен: на паузе тот же трек есть и в мини-плеере, а он шире строки
     * (1272x277 против 1272x266), так что «самый крупный» уверенно тапает не то.
     *
     * Возвращает текст причины, а не просто «не вышло»: три исхода требуют от агента разного
     * и все три правдоподобны. Выключенный сервис доступности, ненарисованный экран и не тот
     * плейлист лечатся по-разному, и обезличенное «кнопка не появилась» отправляет агента
     * чинить не то.
     */
    private fun tapFirstHeadTrack(headTracks: List<HeadTrack>): Tap {
        val first = headTracks.first().title
        val outcome = MediaUiAutomation.perform(
            target = MediaUiTarget(
                packageName = PACKAGE,
                textContains = listOf(first),
                preferTopmost = true,
            ),
            action = MediaUiAction.Click,
            timeoutMs = UI_TIMEOUT_MS,
        )
        return when (outcome) {
            is MediaUiOutcome.Performed -> Tap.Done
            is MediaUiOutcome.NotFound -> {
                val seen = outcome.candidates.size
                Log.w(TAG, "no row for '$first': $seen clickable candidates around")
                Tap.Retry(
                    "no row for the first track '$first' within ${UI_TIMEOUT_SECONDS}s; " +
                        "$seen clickable controls were on screen instead",
                )
            }
            is MediaUiOutcome.Rejected -> {
                Log.w(TAG, "'$first' row refused: ${outcome.reason}")
                Tap.Retry("the row for '$first' was found but the tap did not stick: ${outcome.reason}")
            }
            is MediaUiOutcome.Failed -> {
                Log.w(TAG, "'$first' row failed: ${outcome.reason}")
                Tap.Fatal("the OpenCode accessibility service is not usable: ${outcome.reason}")
            }
        }
    }

    /** Итог тапа: [Done] — нажали, [Retry] — экран ещё не готов, [Fatal] — второй раз не поможет. */
    private sealed interface Tap {
        data object Done : Tap

        data class Retry(
            val reason: String,
        ) : Tap

        data class Fatal(
            val reason: String,
        ) : Tap
    }

    /**
     * Трек из начала плейлиста, который сейчас играет, либо `null` если это не он.
     *
     * Сверяем не со строго первым треком: недоступные треки Яндекс молча выкидывает, и плейлист
     * стартует со следующего играбельного. Требовать именно первый - значит гарантированно
     * врать на любом плейлисте с недоступным head. Поэтому успех - это «играет что-то из
     * начала плейлиста», а настоящая позиция трека попадает в ответ, чтобы вызывающий видел
     * реальную картину.
     */
    internal fun startedTrack(
        actual: String?,
        headTracks: List<HeadTrack>,
    ): HeadTrack? {
        val heard = actual?.takeIf { it.isNotBlank() }?.let { MediaUiNodeMatcher.normalize(it) }
            ?: return null
        return headTracks.firstOrNull { track ->
            // Именно токенное сопоставление, а не посимвольный contains: иначе трек
            // "ONE" из головы плейлиста совпал бы с "Someone Like You" или "Stone", и мы
            // радостно объявили бы успехом чужой трек.
            MediaUiNodeMatcher.containsTerm(heard, track.title)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
