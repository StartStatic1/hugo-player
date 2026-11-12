# VPN WireGuard automática (Hugo Player, build do site)

Código: `app/src/main/java/com/hugo/player/vpn/VpnManager.kt` (biblioteca oficial `com.wireguard.android:tunnel`, GoBackend).
Logs: `adb logcat -s HugoVpn`.

## Quando liga
1. Download da conexão no aparelho (`CatalogRepository.importM3uFullyLocal`) recebe **HTTP 403/451** do provedor
   e existe uma config importada → liga o túnel do país padrão (`US`) e repete o download **uma vez**.
   Se der certo, a fonte fica marcada como "precisa de US" (SharedPreferences `hugo_vpn`, chave `req:<id>`).
   Se falhar, segue o fluxo antigo (rotas alternativas / mensagem de erro).
2. Fonte marcada (aprendida no passo 1 ou vinda do backend com `vpnCountry: "US"` ou `requires_vpn: true`)
   → a VPN liga antes de baixar e antes de abrir o vídeo (PlayerScreen / LivePlayerScreen).
3. Sem config importada, sem permissão ou com "Ligar sozinha" desligado: nada muda, nenhuma exceção.

O túnel cobre só o tráfego do Hugo (`IncludedApplications = pacote do app`), salvo se o .conf já definir
`IncludedApplications`/`ExcludedApplications`. Continua ligado até o usuário desligar em Configurações ou o app fechar.

## Permissão
Na 1ª vez o Android mostra "Permitir conexão VPN" (VpnService.prepare). A MainActivity abre o diálogo; se o usuário
negar ou não houver tela aberta em 60 s, a VPN não liga e o erro original aparece.

## Configs por país
**Automático (padrão):** o dono envia o `.conf` no painel admin (hugo.streamflixvip.online/admin.html › "VPN automática
(WireGuard)"). Quando precisa da VPN e não há config importada à mão, o app chama `GET /vpn/config/<CC>`
(x-device-id + x-device-key; só licença/teste ativo), guarda em `filesDir/vpn-remote/<CC>.conf` (privado do app) e
confere a cada 6 h com `If-None-Match` (hash) se mudou. 404/401/403 apagam a cópia local. Sem config no servidor, não
pergunta de novo por 10 min. Hosts marcados no painel chegam como `vpnCountry` no `GET /playlists`.

**Manual (reserva, tem prioridade sobre a do servidor):**
- Configurações › Cache e histórico (junto do DNS seguro) › "VPN automática (WireGuard)" › Importar .conf (US) — seletor de arquivos.
- Ou copiar o arquivo para `Android/data/com.hugo.player/files/vpn/US.conf` (TVs sem seletor; `adb push`).
- Outros países: `XX.conf` no mesmo lugar e `VpnManager.ensureConnected("XX")` / `vpnCountry: "XX"` no backend.
  A tela hoje só importa US.

## ProtonVPN Free
Entrar em account.protonvpn.com › Downloads › WireGuard configuration › plataforma Android/GNU-Linux,
escolher um servidor Free dos EUA › Create › baixar o `.conf` e importar como acima.
(Conferir se o servidor gratuito escolhido é dos EUA; se o plano Free não oferecer US, usar outro provedor
WireGuard com servidor nos EUA.) Nunca colocar o `.conf` no repositório: tem a chave privada.

## Google Play
O build `play` (branch `play-store`) **não** deve usar isto sem a declaração de VpnService no Play Console.
Ao juntar com a `play-store`: no buildType `play` usar `buildConfigField("boolean", "VPN_ENABLED", "false")` e
remover o serviço no manifesto do play:
`<service android:name="com.wireguard.android.backend.GoBackend$VpnService" tools:node="remove" />`
(a biblioteca declara o serviço sozinha).
