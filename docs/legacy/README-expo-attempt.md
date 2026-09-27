# OpenCode Mobile

Мобильный клиент к [OpenCode](https://opencode.ai). Expo + React Native + TypeScript.

## Два режима

### ☁ Free cloud (без сервера)

Прямой чат через бесплатный провайдер OpenCode Zen — `https://opencode.ai/zen/v1`
(OpenAI-совместимый API, без авторизации). Модель по умолчанию — `big-pickle`,
плюс выбор из других free-моделей (`hy3-free`, `mimo-v2.5-free`, ...).
История сессий хранится на устройстве (AsyncStorage). Общий бесплатный пул
бывает перегружен (`FreeUsageLimitError`) — просто смени модель.

### 🖥 My server

Классика: на компе запускаешь OpenCode в серверном режиме:

```sh
opencode serve --hostname 0.0.0.0 --port 4096
```

В приложении вводишь адрес сервера (например `http://192.168.1.10:4096`) — телефон и комп должны быть в одной сети. Приложение ходит в JSON API сервера: health, список сессий, создание/удаление сессий, история сообщений, отправка промптов, abort.

## Возможности (MVP)

- 📡 Два бэкенда за одним интерфейсом (интерфейс `Backend`): сервер и zen-облако
- 📋 Список сессий (pull-to-refresh, долгое нажатие — удалить)
- 💬 Чат: отправка сообщений, живой вывод через поллинг во время генерации, кнопка stop (abort)
- 🧠 Выбор free-модели в облачном режиме, всё сохраняется между запусками
- 🎨 Тёмная тема

## Запуск

```sh
npm install
npm start          # Expo dev server
npm run android    # сразу на Android
npm run web        # в браузере
```

Typecheck:

```sh
npm run typecheck
```

## Структура

```
App.tsx                  # роутинг: splash -> connect -> sessions -> chat
src/api/backend.ts       # общий интерфейс бэкенда (server | cloud)
src/api/client.ts        # серверный режим: typed fetch к opencode serve
src/api/provider.ts      # облачный режим: chat-completions клиент zen (чистый TS)
src/api/cloudBackend.ts  # облачный режим: Backend поверх провайдера + локальная история
src/api/cloudStore.ts    # локальные сессии/сообщения (AsyncStorage)
src/api/types.ts         # модели Session / Message / Part
src/screens/             # ConnectScreen (режимы+модели), SessionsScreen, ChatScreen
src/storage.ts           # настройки: режим, URL сервера, модель
src/theme.ts             # тёмная палитра
```

## Заметки

- `api.opencode.ai/v1` и `api2.opencode.ai` — не работают (404 / NXDOMAIN).
  Живой адрес zen-шлюза: `https://opencode.ai/zen/v1` (проверено живыми запросами).
- В серверном режиме отправка — синхронный запрос до конца хода ассистента; пока
  идёт генерация, чат поллит историю раз в 2 секунды.
- Облачный режим stateless: история пересылается целиком с каждым запросом;
  SSE и стриминг пока не подключены.
