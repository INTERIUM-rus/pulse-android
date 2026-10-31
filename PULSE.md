# Pulse для Android

Это Android-версия музыкального визуализатора [Pulse](https://interium-rus.github.io/pulse/).

Она основана на [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) (GPL-3.0) и движке [byedpi](https://github.com/hufrea/byedpi).

Отличия от ByeByeDPI:

- **Главный экран — Pulse.**
  - Экран `PulseActivity` показывает веб-версию Pulse во встроенном браузере.
  - Весь его трафик идёт через byedpi в режиме прокси (`socks5://127.0.0.1:1080`).
  - Поэтому YouTube работает без VPN, а остальные приложения телефона не затронуты.
- **Режим byedpi — прокси.** Режим «локальный VPN» не используется, и вопроса «Разрешить VPN?» нет.
- **Настройки обхода открываются из Pulse.** Там есть кнопка «Подобрать способ под оператора»: она открывает проверку ByeByeDPI.
- **Название, иконка и ID пакета Pulse** (`io.github.interium_rus.pulse`).

Сборка:

```
gradlew assembleRelease
```

Для подписи нужны переменные окружения:

- `PULSE_KEYSTORE`
- `PULSE_STORE_PASS`
- `PULSE_KEY_ALIAS`
- `PULSE_KEY_PASS`

Лицензия — GPL-3.0, как у ByeByeDPI (см. LICENSE).
