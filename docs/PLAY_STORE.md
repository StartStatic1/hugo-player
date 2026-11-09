# Build Google Play (Hugo Player)

Build type `play` em `apps/android/app/build.gradle.kts`. Não muda `debug` nem `release`
(APK do site / Fire TV / CI `assembleRelease` continuam iguais, mesmo pacote `com.hugo.player`).

| | site (release) | Google Play (play) |
|---|---|---|
| Pacote | com.hugo.player | com.hugo.player.play |
| versionName | 0.5.64 | 0.5.64-play (mesmo versionCode) |
| Autoatualização por APK | sim | não (sem REQUEST_INSTALL_PACKAGES; "atualizar" abre a Play Store) |
| Compra / link do site | botão "Site oficial" | sem link do site, sem preço; licença inativa = "Teste encerrado. Ative sua licença." + Device ID/Key |
| Entrada de conteúdo | M3U ou Xtream | só servidor/usuário/senha do provedor |
| Textos | playlist / lista | conexão (StoreBuild.wording, pt/en/es) |

Gerar:

```bash
cd apps/android
./gradlew bundlePlay assemblePlay      # sem assinatura
# ou assinado direto: ./gradlew bundlePlay -PhugoPlaySigning=/caminho/hugo-play-upload.properties
```

O `.properties` (fora do repositório) tem `storeFile`, `storePassword`, `keyAlias`, `keyPassword` da chave de UPLOAD.
Na Play Console, ative o Play App Signing (o Google guarda a chave final; a de upload pode ser trocada se perder).
