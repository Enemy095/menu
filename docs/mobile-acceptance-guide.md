# Mobile acceptance guide

Этот сценарий используется перед передачей публичной ссылки на меню и после
изменений cart, filters, dialogs, sticky/fixed элементов или responsive CSS.

## Контрольная среда

Dev server подходит для быстрой вёрстки, но не является acceptance-средой для
iPhone. Итоговая проверка выполняется против production build и работающего API.

```powershell
pnpm validate:full --skip-install
pnpm --filter web test:e2e:production
```

`test:e2e:production` ожидает API на `http://127.0.0.1:8080`, собирает frontend,
поднимает временный production server на `3201` и проверяет:

- desktop Chromium;
- touch tablet Chromium;
- iPhone 11 WebKit;
- iPhone с точным user agent iOS 15.3.1;
- Telegram iOS WebView user agent;
- compact iOS viewport 320 px;
- Android Chromium;
- недоступный `localStorage` с переходом на in-memory cart.

## Стабильная публичная ссылка

Dev server всегда использует `.next-dev`, а production build — `.next`. Поэтому
`next build` больше не может повредить запущенный dev server.

Управляемый скрытый dev server запускается отдельно:

```powershell
pnpm --filter web dev:background
```

Публичный preview также не использует dev-порт `3000`. Одна команда собирает
immutable-копию production `.next`, запускает её на выделенном порту `3202`,
переиспользует уже работающий управляемый ngrok и проверяет оба HTTP endpoint:

```powershell
pnpm --filter web preview:public
```

Процессы запускаются скрыто через Node `spawn`, без `Start-Process` и `cmd start`.
PID и путь immutable release сохраняются в игнорируемом
`apps/web/.runtime/public-preview/state.json`. Повторный запуск корректно заменяет
только preview server. Tunnel и публичный URL сохраняются, пока жив ngrok process.

Frontend закреплён на Next.js 15.5.x, потому что эта поддерживаемая ветка
официально работает с Safari 12+. Next.js 16 требует Safari 16.4+ и не подходит
для физических устройств с iOS 15.3.1.

## Проверка на физическом телефоне

1. Опубликовать production server через tunnel, а не `next dev`.
2. Открыть ссылку сначала в Safari/Chrome, затем через Telegram.
3. Открыть карточку первым нажатием.
4. Изменить количество и добавить позицию.
5. Проверить badge и нижнюю cart bar.
6. Открыть корзину, изменить количество и закрыть её.
7. Применить несколько category filters и сбросить их.
8. Прокрутить страницу до контактов и обратно.
9. Перезагрузить страницу и проверить восстановление корзины.
10. Повторить в private mode: корзина должна работать в рамках текущей вкладки,
    даже если browser storage заблокирован.

## Диагностика hydration

К URL можно добавить `?debug=1`:

```text
https://example.test/?debug=1#menu
```

Панель показывает состояние client bootstrap. В DOM также выставляются признаки:

- `data-app-hydrated="true"` — React client запустился;
- `data-cart-hydrated="true"` — cart store восстановлен;
- `data-cart-storage="memory"` — browser storage недоступен, включён fallback;
- `data-app-hydration-timeout="true"` — client не запустился за контрольное время.

При незавершённом client bootstrap пользователь получает видимое сообщение с
кнопкой обновления. Нижняя cart bar остаётся в DOM и показывает loading state,
вместо бесшумного исчезновения.

## Acceptance criteria

- нет runtime или hydration errors в чистом браузере;
- нет горизонтального overflow;
- touch targets критических действий не меньше 44 × 44 px;
- header, toolbar, cart, backdrop и dish dialog не перекрывают друг друга;
- double tap по кнопкам не запускает browser zoom;
- active, loading и disabled состояния визуально различимы;
- ошибка storage или сети не оставляет cart навсегда заблокированным.
