package org.opencode.mobile.social

import android.util.Log

/**
 * Что означает «уже существует» в ответе на публикацию комментария.
 *
 * Отдельный объект, потому что это ровно то место, где ответ СЕРВЕРА перестаёт быть
 * ответом на наш вопрос. Дедуп у moltbook устроен по тексту и автору, без родителя:
 * тот же самый текст в соседней ветке возвращает id нашего старого комментария оттуда.
 * Ни клиент, ни тик этого не различают — оба видят `already_existed` и живой
 * `verified`-статус, и оба по умолчанию запирают ветку, то есть отвечают «мы уже
 * отвечали» на вопрос, который мы не отвечали.
 *
 * Разводить это можно единственным способом: сравнить родителя. Текст и автор
 * совпадают по построению, поэтому родитель — последнее, что отличает «наш ответ на
 * ЭТОТ вопрос» от «наш ответ на соседний вопрос» (MOLTBOOK_ADVICE 1.5/3.3).
 *
 * Объект, а не пара функций в тикере, по трём причинам: решение чистое и проверяется
 * тестом без базы и без устройства; оно ничего не знает про сеть, поэтому его нечего
 * тащить в клиент; и рядом с ним живёт [UNKNOWN_PARENT] — константа, которая обязана
 * означать ровно одно и не должна разъезжаться с местом, где её читают.
 */
internal object MoltbookDedupCheck {
    private const val TAG = "MoltbookDedup"

    /** Родитель в ответе сервера не пришёл вовсе: это «не знаю», а не «другой». */
    const val UNKNOWN_PARENT = "unknown-parent"

    /**
     * Ответ сервера «уже существует» — это ещё не «наш ответ на этот вопрос».
     *
     * Разводит [MoltbookTicker.PostResult.Reused] и [MoltbookTicker.PostResult.Misparented]
     * по единственному признаку: совпадает ли родитель у возвращённого комментария с тем,
     * куда мы писали.
     *
     * Отсутствие `parent_id` — это «не знаю», а не «другой». Но проверять нечем, поэтому
     * уверенно закрыть ветку нельзя, и она уходит в `Misparented` с пустым фактическим
     * родителем: виноват сервер, а не мы, и трогать его живой коммент мы не будем ни
     * при каком раскладе. Это ровно тот случай `CONFIRMATION_INDETERMINATE`, о котором
     * MOLTBOOK_ADVICE 1.2/3.2 говорит «не удалять, не повторять, не считать».
     *
     * Оба исхода ничего не публикуют и ничего не удаляют — разница только в том, чем тик
     * объяснит своё решение: «уже отвечено ранее» или «ответ не засчитан».
     */
    internal fun reuseOrMisparent(
        duplicate: MoltbookClient.CommentOutcome.Duplicate,
        parentId: String,
    ): MoltbookTicker.PostResult =
        if (duplicate.existingParentId == parentId) {
            MoltbookTicker.PostResult.Reused(duplicate.existingCommentId)
        } else {
            Log.w(
                TAG,
                "already_existed вернул чужой родитель: ждали $parentId, " +
                    "получили ${duplicate.existingParentId ?: UNKNOWN_PARENT}",
            )
            MoltbookTicker.PostResult.Misparented(
                duplicate.existingCommentId,
                parentId,
                duplicate.existingParentId,
            )
        }
}
