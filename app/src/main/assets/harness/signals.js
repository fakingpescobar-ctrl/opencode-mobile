#!/usr/bin/env bun
// signals.js - поиск собственных ошибок агента в его логе.
//
// Зачем
// -----
// Агент чинит чужой код, чинит MCP-серверы и сам себе ставит память. Свои
// ошибки он не видит: они остаются в логе строчкой в три часа ночи. Этот
// модуль - первый шаг контура, который читает свои логи.
//
// Главное правило модуля
// ----------------------
// Он НЕ делает выводов. Он умеет только три вещи: посчитать, как часто
// повторяется одна и та же неудача, показать доказательство и сказать, сколько
// строк он просмотрел. Причину придумывает агент, а не модуль.
//
// Почему так жёстко: контур, который сам объясняет себе логи, начинает
// галлюцинировать причины и рано или поздно «находит» ошибку, которой нет, -
// а потом патчит под неё рабочий код. Модуль, который умеет только считать,
// не может солгать о причине: у него нет для этого ни данных, ни поля, в
// котором это поле можно было бы положить.
//
// Второе правило: отсутствие сигналов не должно читаться как «всё хорошо».
// Потому возвращаются error_lines, suppressed и verdict. Лог на 9000 строк, в
// котором ошибок нет, и лог, который никто не открывал, - это разные вещи,
// и результат обязан это различать.
//
// Формат лога здесь не предполагается никакой: модуль знает только то, что
// общий у всех логов, - признаки ошибки в строке. Разбор формата конкретного
// сервера живёт в самом сервере.
//
// Модуль чистый: принимает строки, отдаёт структуру. Файлы не читает.

export const MIN_COUNT = 2;
export const MAX_SIGNALS = 20;

// Признаки ошибки в строке. Список общий и не привязан к формату: по логу
// с чужого сервера нельзя узнать, что именно этот сервер пишет при сбое, но
// «error», «timeout», «denied» встречаются везде.
// Проверено на живом логе: 27 строк-кандидатов из 9498, ни одна из
// отброшенных не оказалась мусором - hex-дампы сюда не попадают, потому что
// символы 'o' и 'r' не входят в шестнадцатеричный диапазон.
const ERROR_MARKERS =
  /\b(error|errors|failed|failure|fail|exception|denied|refused|timeout|timed out|fatal|panic|crash|aborted|unavailable)\b/i;

// Порядок нормализации задан и не переставляется: каждый шаг убирает то, что
// меняется от запуска к запуску. Пропуск любого шага означает, что две
// одинаковые неудачи перестанут быть одинаковыми.

// Конверт с JSON внутри меняется целиком: там rid, временные метки, счётчики.
// Без этого две строки одного и того же отказа никогда не совпадут. Человеческая
// часть - поле message - как раз и говорит о причине, поэтому она и остаётся.
function pullErrorMessage(line) {
  const match = line.match(/"message"\s*:\s*"([^"]{3,200})"/);
  if (!match) return line;
  // Вместе с конвертом уходит и открывающая скобка: в подписи она остаётся
  // мусором, который агент будет читать как часть причины.
  return line.replace(/\{\s*"error"\s*:\s*\{[\s\S]*$/, "") + " message: " + match[1];
}

// Длинные шестнадцатеричные куски - rid, хеши, ключи. Считать их частью
// подписи нельзя: они случайны.
function maskHex(line) {
  return line
    .replace(/0x[0-9a-f]+/gi, "HEX")
    .replace(/\b[0-9a-f]{12,}\b/gi, "HEX");
}

function maskUuid(line) {
  return line.replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, "UUID");
}

// Все числа подряд: длительности ("~36s", "~72s"), размеры ("5000B"), порты,
// номера попыток ("attempt 1/2/3"), коды. Попытка 1 и попытка 3 - это одна и
// та же поломка, и различать их нельзя: иначе каждая попытка станет
// единичным сигналом, не дотянет до порога, и закономерность просто исчезнет.
// Все подробности остаются в example - там цифры настоящие.
function maskDigits(line) {
  return line.replace(/\d+/g, "N");
}

function normalize(line) {
  return maskDigits(maskUuid(maskHex(pullErrorMessage(line))))
    .replace(/\s+/g, " ")
    .trim();
}

// Отпечаток подписи: стабильный, без внешних зависимостей. Нужен, чтобы не
// докладывать один и тот же сигнал каждый запуск. FNV-1a достаточно: коллизия
// здесь означала бы лишь «два разных отказа посчитались одним», что видно по
// разным подписям в выдаче.
export function fingerprint(signature) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < signature.length; i++) {
    hash ^= signature.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return "sig:" + hash.toString(16).padStart(8, "0");
}

/**
 * Ищет повторяющиеся отказы в строках лога.
 *
 * lines - массив строк. Порядок сохраняется в first_line/last_line.
 * opts.minCount - с какого числа повторов отказ считается закономерностью.
 * opts.maxSignals - сколько самых частых отдать. Остальное - в `dropped`,
 *                  чтобы тишина в выдаче не читалась как «других нет».
 * opts.known - массив отпечатков, которые докладывать не нужно (уже в памяти).
 */
export function extractSignals(lines, opts = {}) {
  if (!Array.isArray(lines)) throw new Error("extractSignals ждёт массив строк лога");

  const minCount = Math.max(2, Math.floor(Number(opts.minCount) || MIN_COUNT));
  const maxSignals = Math.max(1, Math.floor(Number(opts.maxSignals) || MAX_SIGNALS));
  const known = new Set(Array.isArray(opts.known) ? opts.known : []);

  const groups = new Map();
  let errorLines = 0;

  for (let index = 0; index < lines.length; index++) {
    const line = String(lines[index] == null ? "" : lines[index]);
    if (!ERROR_MARKERS.test(line)) continue;
    errorLines++;

    const signature = normalize(line);
    const seen = groups.get(signature);
    if (seen) {
      seen.count++;
      seen.lastLine = index + 1;
      continue;
    }
    groups.set(signature, {
      signature,
      count: 1,
      firstLine: index + 1,
      lastLine: index + 1,
      // Доказательство целиком, цифры настоящие: по нему агент может
      // проверить, что отказ реальный, а не придуманный нормализацией.
      example: line.trim().slice(0, 500),
    });
  }

  // Порядок детерминированный: чаще - значит важнее, при равном счёте раньше
  // встретившийся. Иначе выдача прыгала бы между запусками и прошлый сигнал
  // не находился бы среди новых.
  const sorted = [...groups.values()].sort(
    (a, b) => b.count - a.count || a.firstLine - b.firstLine
  );

  const repeated = sorted.filter(g => g.count >= minCount);
  const suppressed = sorted.filter(g => g.count < minCount);

  const signals = repeated.slice(0, maxSignals).map(g => ({
    fingerprint: fingerprint(g.signature),
    signature: g.signature,
    count: g.count,
    first_line: g.firstLine,
    last_line: g.lastLine,
    example: g.example,
    already_known: known.has(fingerprint(g.signature)),
  }));

  return {
    scanned_lines: lines.length,
    error_lines: errorLines,
    min_count: minCount,
    signals,
    // Сколько отказов не дотянуло до порога и сколько это всего происшествий.
    suppressed_groups: suppressed.length,
    suppressed_occurrences: suppressed.reduce((sum, g) => sum + g.count, 0),
    // Сигналы, помещённые в `repeated`, но не показанные из-за лимита.
    dropped: repeated.length - signals.length,
    verdict: verdict(lines.length, errorLines, signals, suppressed.length, minCount),
  };
}

function verdict(scannedLines, errorLines, signals, suppressedGroups, minCount) {
  // Пустой лог - это не «всё хорошо». Это «лога нет»: не тот путь, не тот
  // файл, сервер не стартовал. Объявлять здоровье по отсутствию записей -
  // ровно та ошибка, ради которой этот модуль и писался.
  if (scannedLines === 0) {
    return "Лог пуст: просмотреть нечего. Отсутствие ошибок здесь ничего не " +
      "доказывает - скорее всего, взят не тот файл или сервер не писал лог.";
  }
  if (errorLines === 0) {
    return `Лог чист: ${scannedLines} строк просмотрено, строк с признаками ошибки нет.`;
  }
  if (signals.length === 0) {
    return `Просмотрено ${scannedLines} строк, строк с признаками ошибки ${errorLines}. ` +
      `Ни один отказ не повторился ${minCount} раз (единичных групп: ${suppressedGroups}) - ` +
      `это разовые сбои, а не закономерность. Причина не ищется по одному случаю.`;
  }
  const top = signals[0];
  return `Просмотрено ${scannedLines} строк, строк с признаками ошибки ${errorLines}. ` +
    `Повторяющихся отказов: ${signals.length}, самый частый - «${top.signature}» (${top.count} раз). ` +
    `Причина не определяется модулем: evidence в поле example.`;
}