# Формат content.json

Публичный контент телевизоров (без персональных данных). Телевизор скачивает файл по адресу из настроек
(`contentUrl`) при запуске и раз в 15 минут, поддерживает `ETag` / `304 Not Modified`, хранит последнюю рабочую
версию на диске. Ошибочный ответ (не JSON, нет `hotel.name`) кеш не затирает. Без адреса — встроенный демо-контент
(`app/src/main/assets/content-demo.json`, полный пример всех полей).

Изображения: `https://…` (кешируются на диске, доступны без сети после первой загрузки), `asset:///…` (встроенные).
Для 4K-экранов — фон 3840×2160, баннеры от 2000 px по ширине, JPEG/WebP.

Тексты — строка или объект языков: `"title": "Ресторан"` или `"title": {"ru": "Ресторан", "en": "Restaurant", "kk": "Мейрамхана"}`.
Нет нужного языка — берётся русский, затем английский.

```jsonc
{
  "version": 3,                       // номер версии, виден в сервисном меню
  "language": "ru",                   // язык интерфейса по умолчанию (ru | en | kk)
  "languages": ["ru", "en", "kk"],
  "hotel": {
    "name": "TENIR",                  // обязательно
    "tagline": {"ru": "Hotel Shymbulak · 3200 м"},
    "logo": "https://cms.example/tenir/logo.png",   // null — встроенный знак
    "background": "https://cms.example/tenir/bg-4k.jpg",
    "accentColor": "#C9A96E",         // цвет фокуса и акцентов
    "welcome": {"ru": "Добро пожаловать"},          // приветствие без имени
    "phone": "100",
    "wifi": {"ssid": "TENIR-Guest", "password": "…"},
    "info": {"ru": "Текст раздела «Об отеле», переносы строк — \n"}
  },
  "display": {
    "showClock": true, "showWeather": true,
    "showGuestName": true,
    "guestNameFormat": "first_name",  // first_name | full | none
    "showRoomNumber": true,
    "bannerIntervalSeconds": 9,       // 3–120
    "use24h": true,
    "timezone": "Asia/Almaty"         // часовой пояс отеля для часов и правила выезда
  },
  "home": {
    "tiles": ["restaurant", "netflix", "youtube", "news", "apps", "info"]   // набор и порядок плиток
  },
  "banners": [
    {
      "id": "sunset", "label": {"ru": "Каждый вечер"},
      "title": {"ru": "Закат над пиком Талгар"}, "subtitle": {"ru": "Терраса открыта до 22:00"},
      "image": "https://…",
      "action": {"type": "restaurant"}   // none | restaurant | news (target = id новости) | app (target = id из apps) | apps | info
    }
  ],
  "news": [
    {"id": "skipass", "date": "2026-10-15", "title": {…}, "summary": {…}, "body": {…}, "image": "https://…"}
  ],
  "restaurant": {
    "name": {…}, "description": {…}, "hours": {…}, "orderInfo": {…}, "image": "https://…", "currency": "₸",
    "categories": [
      {"id": "mains", "name": {…}, "items": [
        {"id": "beshbarmak", "name": {…}, "description": {…}, "price": 7900, "image": null, "tags": ["hit"]}
      ]}
    ]
  },
  "apps": [                           // плитки внешних приложений; видны, только если пакет установлен
    {"id": "netflix", "title": "Netflix", "packages": ["com.netflix.ninja", "com.netflix.mediaclient"], "color": "#E50914"},
    {"id": "youtube", "title": "YouTube", "packages": ["com.google.android.youtube.tv", "com.google.android.youtube"], "color": "#FF0033"}
  ],
  "weather": {"latitude": 43.1286, "longitude": 77.0816, "label": {"ru": "Шымбулак"}},   // Open-Meteo, без ключа
  "hub": {
    "pollSeconds": 60,                // опрос Hotel Hub, 15–3600
    "maxAgeMinutes": 10,              // сколько показывать имя без нового подтверждения хаба
    "checkoutHour": 12,               // после этого часа в день выезда имя скрывается; 24 — весь день
    "clearAppsOnCheckout": ["com.netflix.ninja", "com.google.android.youtube.tv"]   // нужен режим владельца устройства
  }
}
```

Сломанные элементы списков (баннер без заголовка и картинки, приложение без пакетов, координаты вне диапазона)
пропускаются, остальной документ применяется. Новые плитки, отсутствующие в `home.tiles`, не показываются; плитка
приложения без установленного пакета скрывается.

Новый раздел: добавить экран (`BaseTvActivity`), id плитки в `Tiles` и ветку в `HomeActivity.renderTiles`,
поля — в `Content.kt` и `ContentParser`.
