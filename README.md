# securegate

Один домен и один порт **443** для всего:

| Что приходит на 443 | Куда попадает |
|---|---|
| SSTP VPN-клиент (Keenetic, Windows, sstpc) | встроенный SSTP-сервер (Go) + pppd, NAT в интернет |
| Telegram с прокси `ee…` (MTProxy FakeTLS) | [telemt](https://github.com/telemt/telemt) |
| Android-клиент чата | чат-сервер (REST + WebSocket, только транспорт) |
| WebRTC TURN-over-TLS | встроенный TURN (pion/turn) |
| Браузер | демо-сайт |
| Всё остальное / сканеры | штрафные баллы → бан IP (nftables) |

Порт 80 — только Let's Encrypt (HTTP-01) и редирект на https. Сертификат запрашивается и
продлевается автоматически (проверка каждые 12 ч, продление за 30 дней до истечения).

## Установка

Требования: Linux-сервер с Docker и Docker Compose, публичный IP, DNS-запись `A` домена на этот IP,
открытые порты `80/tcp`, `443/tcp`, а для качественных звонков — `3478/udp` и `49160-49200/udp`.

```bash
git clone <адрес репозитория> securegate
cd securegate
cp .env.example .env
nano .env            # DOMAIN, ACME_EMAIL, SSTP_USERS, MTPROXY_USERS, CHAT_SECRET
docker compose up -d --build
docker compose logs -f gateway
```

### Firewall (ufw)

Если на сервере используется ufw — откройте порты (сначала обязательно SSH, иначе после
`ufw enable` вы потеряете доступ к серверу):

```bash
sudo ufw allow 22/tcp comment "SSH"
sudo ufw allow 80/tcp comment "securegate ACME + redirect"
sudo ufw allow 443/tcp comment "securegate SSTP/MTProxy/chat/site"
sudo ufw allow 3478/udp comment "securegate STUN/TURN"            # = TURN_UDP_PORT
sudo ufw allow 49160:49200/udp comment "securegate TURN relay"    # = TURN_MIN_PORT:TURN_MAX_PORT
sudo ufw enable
sudo ufw status numbered
```

Если меняли `TURN_UDP_PORT` / `TURN_MIN_PORT` / `TURN_MAX_PORT` в `.env` — подставьте свои значения.
Правила NAT/FORWARD для SSTP шлюз ставит сам, менять политику ufw не нужно (проверено:
VPN-клиенты выходят в интернет при включённом ufw, в том числе после `ufw reload`).
У облачного провайдера (Security Groups / сетевой firewall в панели) те же порты нужно открыть отдельно.

Опционально: `cp config/users.yml.example config/users.yml` — пользователи SSTP/MTProxy и белый
список телефонов чата в YAML (объединяется с `.env`). После изменений: `docker compose restart`.

## SSTP (Keenetic)

Пользователи — `SSTP_USERS='keenetic:пароль'` (через запятую) или `config/users.yml`.
На Keenetic: *Другие подключения → VPN → SSTP*, адрес сервера — ваш домен, имя и пароль из настроек.
Клиенты получают адреса из `SSTP_POOL` (по умолчанию 192.168.17.0/24, сервер 192.168.17.1),
DNS — `SSTP_DNS`. Трафик выпускается в интернет через NAT (правила ставятся и снимаются автоматически).

## MTProxy

`MTPROXY_USERS=имя:32hex,имя2:32hex` (секрет: `openssl rand -hex 16`) или `mtproxy_users` в YAML.
Готовые ссылки `tg://proxy?...` печатаются в логе шлюза и сохраняются в `data/mtproxy-links.txt`.
Работает режим FakeTLS (ee): для внешнего наблюдателя это обычный HTTPS вашего домена.

## Чат

`CHAT_SECRET` — секретный ключ, который пользователи вводят в приложении вместе с доменом и своим
номером телефона. Из него же выводится ключ шифрования сообщений (AES-256-GCM) — сервер переписку
не хранит и не может прочитать: только пересылает и держит зашифрованную очередь для оффлайн-получателей.
Максимум пользователей — `CHAT_MAX_USERS` (100), белый список телефонов — `CHAT_ALLOWED_PHONES`.
Промт для генерации Android-клиента в Google AI Studio — `client-promt.txt` (там же полная спецификация API).

## Защита

* лимиты: одновременные соединения и частота новых соединений на IP;
* штрафы: не-TLS на 443, чужой SNI, битый TLS, ошибки входа SSTP/чата, сканирование путей
  (`/.env`, `/wp-login.php`…), сканеры по User-Agent, 404;
* `GUARD_STRIKES` баллов за 10 минут → бан на `GUARD_BAN_MINUTES` (повторно — вдвое дольше, до 24 ч),
  бан дублируется в nftables (пакеты отбрасываются ядром, SSH не затрагивается);
* `robots.txt: Disallow: /`, `X-Robots-Tag: noindex`, поисковые боты получают 403;
* `GUARD_WHITELIST` — IP/подсети, которые никогда не банятся.

Управление банами:

```bash
docker compose exec gateway gateway bans          # список
docker compose exec gateway gateway unban 1.2.3.4 # разбан
docker compose exec gateway gateway unban all
```

## Данные

Всё состояние в `./data`: сертификаты (`acme/`), баны (`guard/`), база чата и временные файлы
(`chat/`), конфиг telemt (`mtproxy/`). Бэкап — копия этой папки и `.env`.

## Тесты

```bash
sh tests/sstp-client/run.sh <ip> <domain> <user> <password>     # SSTP end-to-end (sstpc)
python3 tests/mtproxy_faketls_test.py <ip> <domain> <32hex>      # MTProxy FakeTLS
sh tests/run-chat-test.sh <ip> <domain> <chat_secret>            # весь API чата
```

Для теста без публичного домена: `TLS_MODE=selfsigned`, `PUBLIC_IP=<ip сервера>`.
