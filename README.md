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

Дополнительно:

| Порт | Что |
|---|---|
| `51820/udp` | WireGuard VPN (клиенты: `./sgctl wg add <имя>` — сразу QR-код для телефона) |
| `2053/tcp` | MTProxy №2 — [mtprotoproxy](https://github.com/alexbers/mtprotoproxy) (Python) |
| `2083/tcp` | MTProxy №3 — [teleproxy](https://github.com/teleproxy/teleproxy) (C, форк официального MTProxy) |

Пользователи MTProxy общие для всех трёх реализаций (задаются один раз в `MTPROXY_USERS`).

Порт 80 — только Let's Encrypt (HTTP-01) и редирект на https. Сертификат запрашивается и
продлевается автоматически (проверка каждые 12 ч, продление за 30 дней до истечения).

## Установка

Требования: Linux-сервер с Docker и Docker Compose, публичный IP, DNS-запись `A` домена на этот IP,
открытые порты `80/tcp`, `443/tcp`, `51820/udp` (WireGuard), `2053/tcp` и `2083/tcp` (MTProxy №2 и №3),
а для качественных звонков — `3478/udp` и `49160-49200/udp`.

```bash
git clone <адрес репозитория> securegate
cd securegate
cp .env.example .env
nano .env            # DOMAIN, ACME_EMAIL, SSTP_USERS, MTPROXY_USERS, CHAT_SECRET
docker compose up -d --build
docker compose logs -f gateway
```

Docker Compose: плагин `docker compose` или отдельный `docker-compose` версии 2 (с ним команды те же,
через дефис). `./sgctl up | ps | logs | restart | down` сам выбирает установленный вариант.
Старый `docker-compose` 1.x не поддерживается: с современным Docker он не может пересоздать
контейнеры при обновлении — поставьте v2 (`apt install docker-compose-v2`).

### Firewall (ufw)

Если на сервере используется ufw — откройте порты (сначала обязательно SSH, иначе после
`ufw enable` вы потеряете доступ к серверу):

```bash
sudo ufw allow 22/tcp comment "SSH"
sudo ufw allow 80/tcp comment "securegate ACME + redirect"
sudo ufw allow 443/tcp comment "securegate SSTP/MTProxy/chat/site"
sudo ufw allow 3478/udp comment "securegate STUN/TURN"            # = TURN_UDP_PORT
sudo ufw allow 49160:49200/udp comment "securegate TURN relay"    # = TURN_MIN_PORT:TURN_MAX_PORT
sudo ufw allow 51820/udp comment "securegate WireGuard"           # = WG_PORT
sudo ufw allow 2053/tcp comment "securegate MTProxy mtprotoproxy" # = MTPROTOPROXY_PORT
sudo ufw allow 2083/tcp comment "securegate MTProxy teleproxy"    # = TELEPROXY_PORT
sudo ufw enable
sudo ufw status numbered
```

Если меняли `TURN_UDP_PORT` / `TURN_MIN_PORT` / `TURN_MAX_PORT` / `WG_PORT` / `MTPROTOPROXY_PORT` /
`TELEPROXY_PORT` в `.env` — подставьте свои значения.
Правила NAT/FORWARD для SSTP и WireGuard контейнеры ставят сами, менять политику ufw не нужно (проверено:
VPN-клиенты выходят в интернет при включённом ufw, в том числе после `ufw reload`).
У облачного провайдера (Security Groups / сетевой firewall в панели) те же порты нужно открыть отдельно.

Опционально: `cp config/users.yml.example config/users.yml` — пользователи SSTP/MTProxy и белый
список телефонов чата в YAML (объединяется с `.env`). После изменений: `docker compose restart`.

## Режим прокси (второй сервер — дополнительная точка входа)

Второй сервер с этим же ПО можно поставить «перед» основным: клиенты подключаются к **домену
прокси**, и через него работает всё — SSTP, MTProxy, чат (включая звонки), сайт.
Прокси не расшифровывает трафик: 443/tcp, 80/tcp и 3478/udp прозрачно пересылаются на основной
сервер с заголовком PROXY v2, поэтому основной видит реальные IP клиентов (баны и лимиты работают
по ним), а SSTP crypto binding и FakeTLS MTProxy проходят как есть.

Через прокси также работают WireGuard (`51820/udp` пересылается на основной) и MTProxy №2/№3
(`2053/tcp`, `2083/tcp`). Прокси может быть сколько угодно — каждый со своим доменом.

**Сертификат Let's Encrypt для домена прокси** выпускает и продлевает основной сервер
(TLS терминирует он): проверка Let's Encrypt приходит на IP прокси (порт 80 или 443) и
пересылается на основной. Ничего дополнительно настраивать не нужно.

1. DNS: `A`-запись домена прокси → IP прокси-сервера.
2. На **прокси-сервере** — `git clone`, `.env` только с тремя параметрами (остальное игнорируется):

   ```bash
   PROXY=true
   DOMAIN=proxy.example.com          # свой домен прокси
   DOMAIN_TO_PROXY=vpn.example.com   # домен основного сервера
   ```

   `docker compose -f docker-compose.proxy.yml up -d --build` — лёгкий вариант (один контейнер ~23 МБ,
   сборка укладывается в 512 МБ RAM; обычный `docker compose up -d --build` тоже работает, но ему
   нужно ~2 ГБ RAM для сборки). Firewall: 80/tcp, 443/tcp, 2053/tcp, 2083/tcp, 3478/udp, 51820/udp
   (`PROXY_UDP=false` — не пересылать UDP STUN/TURN). В логе: `proxy: main server reachable`.
3. На **основном сервере** в `.env` перечислить все прокси и перезапустить:

   ```bash
   # домены всех прокси через запятую (IP-адрес в этом списке = прокси без домена)
   PROXY_FRONTENDS=proxy1.example.com,proxy2.example.org,proxy3.example.net
   # только если прокси выходят в интернет не с IP из своих A-записей (IP или подсети через запятую)
   # PROXY_TRUSTED_IPS=203.0.113.7,198.51.100.0/28
   docker compose up -d
   ```

   Адреса каждого домена прокси перепроверяются каждые 5 минут независимо: если DNS одного домена
   временно не отвечает, для него сохраняются последние известные адреса, остальные не затрагиваются.

   В логе появятся `certs: certificate OK domain=proxy.example.com` и ссылки MTProxy для домена
   прокси (`data/mtproxy-links.txt`). Заголовок PROXY принимается только с IP, в который
   резолвится домен прокси (обновляется каждые 5 минут), поэтому подделать IP клиента нельзя.

Клиентам: Keenetic — адрес сервера = домен прокси; Telegram — ссылка `tg://proxy` с доменом прокси
(`./sgctl mtproxy`); приложение чата — домен прокси (тот же `CHAT_SECRET`, пользователи и переписка
общие); WireGuard — конфиг с адресом прокси: на основном `./sgctl wg show <имя> --via proxy1.example.com`
(ключи те же, клиент один — меняется только `Endpoint`).
Баны действуют на основном сервере: забаненный IP отсекается и через прокси.

## MTProxy на прокси с выходом через WireGuard основного (install-wg-only.txt)

Вариант из двух серверов: на ПРОКСИ работают gateway (443) + telemt + WireGuard-клиент, на ОСНОВНОМ —
только WireGuard-сервер. Исходящие соединения telemt к Telegram идут через туннель и выходят в интернет
с основного; остальной трафик прокси идёт как обычно.

- основной: `WG_MODE=server`, `docker compose up -d --build wireguard`, `./sgctl wg add proxy1 --via <IP основного>`;
- прокси: `WG_MODE=uplink`, `MTPROXY_UPSTREAM_IFACE=sgup0`, `./sgctl wg uplink import proxy1.conf`,
  `docker compose up -d --build gateway mtproxy wireguard`; статус — `./sgctl wg uplink`;
- kill-switch: с прокси в сети Telegram можно только через `sgup0` (туннель упал — прямого трафика нет);
- `WG_AUTO_FIX=true` (на обоих, на основном + `WG_AUTO_FIX_PEERS=proxy1`) — пересоздание WG-интерфейса,
  если через туннель нет трафика; прокси реагирует первым, после перезапуска — ожидание (grace, удваивается).

Пошагово для чистых Ubuntu 22.04 — `install-wg-only.txt`.

## SSTP (Keenetic)

Пользователи — `SSTP_USERS='keenetic:пароль'` (через запятую) или `config/users.yml`.
На Keenetic: *Другие подключения → VPN → SSTP*, адрес сервера — ваш домен, имя и пароль из настроек.
Клиенты получают адреса из `SSTP_POOL` (по умолчанию 192.168.17.0/24, сервер 192.168.17.1),
DNS — `SSTP_DNS`. Трафик выпускается в интернет через NAT (правила ставятся и снимаются автоматически).

## WireGuard

Сервер WireGuard поднимается автоматически (контейнер `wireguard`, ядерный модуль, при его
отсутствии — wireguard-go). Клиенты добавляются одной командой, конфиг сразу показывается
QR-кодом прямо в терминале — сканируется приложением WireGuard на телефоне (`+` → «Сканировать QR-код»):

```bash
./sgctl wg add phone-ivan                 # новый клиент: конфиг + QR-код
./sgctl wg show phone-ivan                # показать QR ещё раз
./sgctl wg show phone-ivan --via proxy1.example.com   # тот же клиент через прокси
./sgctl wg conf phone-ivan > ivan.conf    # только файл конфигурации (для ПК / роутера)
./sgctl wg list                           # клиенты, адреса, последнее подключение, трафик
./sgctl wg remove phone-ivan              # удалить (отключается сразу)
```

Добавление и удаление клиентов — без перезапуска. Ключ сервера и клиенты хранятся в
`./data/wireguard` (volume) — переживают `docker compose down`, пересборку и обновление;
бэкап — копия этой папки. Настройки в `.env`: `WG_PORT` (51820), `WG_SUBNET` (10.17.0.0/24, сервер .1),
`WG_DNS`, `WG_MTU`, `WG_ALLOWED_IPS` (что клиент пускает в туннель), `WG_ENDPOINT` (адрес в конфигах,
по умолчанию `DOMAIN`). Интерфейс на хосте — `sgwg0`, NAT/FORWARD-правила ставятся и снимаются сами.

## Маскирующийся VPN (экспериментально)

Собственный зашифрованный VPN, спрятанный **внутри обычного HTTPS** к сайту на порту 443. Для
наблюдателя и для активного зонда (который просто откроет адрес в браузере) это обычный сайт —
энциклопедия пород кошек и собак; туннель получает только клиент, знающий секрет пользователя
(Reality-подобная маскировка). Проходит и через прокси (это обычный HTTPS/443, прокси видит реальный
IP клиента через PROXY v2). Включается `VPN_ENABLE=true`; по умолчанию выключен.

```bash
./sgctl vpn add phone-ivan                 # новый пользователь: конфиг + QR-код в терминале
./sgctl vpn show phone-ivan                # показать QR ещё раз
./sgctl vpn show phone-ivan --via proxy1.example.com   # тот же пользователь через прокси
./sgctl vpn conf phone-ivan > ivan.sgvpn   # только конфиг (файл для импорта в приложении)
./sgctl vpn list                           # пользователи
./sgctl vpn remove phone-ivan              # отозвать (доступ пропадает в течение ~15 с)
```

QR-код и файл `.sgvpn` сканируются/импортируются Android-приложением (отдельное от SSTP-клиента).
Секреты пользователей хранятся в `./data/maskvpn/users/` (добавление/удаление без перезапуска).
Сервер маршрутизирует трафик через TUN-интерфейс `sgvpn0` с NAT в интернет (пул `VPN_SUBNET`,
по умолчанию 10.18.0.0/24); правила ставятся и снимаются автоматически (как у WireGuard).
Статус: серверная часть (маскировка, аутентификация, шифрование ChaCha20-Poly1305, L3-туннель с NAT,
работа через прокси) готова и проверена; Android-приложение — в разработке.

## MTProxy

`MTPROXY_USERS=имя:32hex,имя2:32hex` (секрет: `openssl rand -hex 16`) или `mtproxy_users` в YAML —
**один список для всех трёх реализаций**:

| Реализация | Порт | Язык |
|---|---|---|
| [telemt](https://github.com/telemt/telemt) | 443 (общий с остальными сервисами) | Rust |
| [mtprotoproxy](https://github.com/alexbers/mtprotoproxy) | `MTPROTOPROXY_PORT` = 2053 | Python |
| [teleproxy](https://github.com/teleproxy/teleproxy) | `TELEPROXY_PORT` = 2083 | C |

Если у клиентов перестала работать одна — дайте ссылку на другую. Порты 2053/2083 устроены как 443:
шлюз проверяет секрет клиента, Telegram уходит в «свою» реализацию, всем остальным — сайт с
настоящим сертификатом (защита и баны действуют). Отключить: `*_ENABLE=false` или порт `0`.
Ссылки для каждой реализации и каждого домена (основного и прокси) — `./sgctl mtproxy`
(или `data/mtproxy-links.txt`), QR-код ссылки — `./sgctl mtproxy qr <user> [telemt|mtprotoproxy|teleproxy] [домен]`.
В логе контейнера mtprotoproxy печатается ссылка с внутренним портом 8444 — её не использовать.
Работает режим FakeTLS (ee): для внешнего наблюдателя это обычный HTTPS вашего домена.
Клиент проверяется по секрету, поэтому принимаются и ссылки с другим доменом внутри секрета
(например, старые ссылки после смены `DOMAIN`). Каждое подключение видно в логе шлюза:
`mtproxy: client ip=… user=… sni=…`. Без `MTPROXY_AD_TAG` telemt ходит в DC Telegram напрямую.

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
./sgctl bans ; ./sgctl unban <ip|all>             # то же через sgctl
```

## Данные

Всё состояние в `./data`: сертификаты (`acme/`), баны (`guard/`), база чата и временные файлы
(`chat/`), конфиги MTProxy (`mtproxy/`, генерируются шлюзом), ключи и клиенты WireGuard
(`wireguard/`). Бэкап — копия этой папки и `.env`.

## Тесты

```bash
sh tests/sstp-client/run.sh <ip> <domain> <user> <password>     # SSTP end-to-end (sstpc)
python3 tests/mtproxy_faketls_test.py <ip> <domain> <32hex>      # MTProxy FakeTLS
python3 tests/mtproxy_e2e_test.py <ip> <domain> <32hex> [443] [dc]  # MTProxy до DC Telegram (pip install cryptography)
sh tests/run-chat-test.sh <ip> <domain> <chat_secret>            # весь API чата
sh tests/wg_test.sh <ip> <domain или домен прокси> [клиент]       # WireGuard (wg-quick в контейнере)
```

MTProxy №2/№3 — тот же `mtproxy_e2e_test.py` с портом 2053 / 2083.

Для теста без публичного домена: `TLS_MODE=selfsigned`, `PUBLIC_IP=<ip сервера>`.

Режим прокси на одной тестовой машине: `sh tests/proxy-sandbox/up.sh <ip основного> <домен основного> <домен прокси>`
`[ip] [имя контейнера]` поднимает экземпляр `PROXY=true` в docker-сети `sgproxy` (по умолчанию 172.30.0.10;
второй — например `172.30.0.11 sg-proxy-test2`); на основном `PROXY_FRONTENDS=<домены прокси>`,
`PROXY_TRUSTED_IPS=<их IP>`. Те же тесты с `DOCKER_NET=sgproxy`
и адресом 172.30.0.10. `ACME_DIRECTORY` — свой ACME-сервер (другой CA / тестовый Pebble).
