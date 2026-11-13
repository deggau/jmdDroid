# JMDroid

[![Build Android app](https://github.com/deggau/jmdDroid/actions/workflows/android.yml/badge.svg)](https://github.com/deggau/jmdDroid/actions/workflows/android.yml)

Aplicativo Android para, em etapas, conectar o celular ao JMD executado no computador. O projeto está em [`android-app/`](android-app/).

A primeira versão contém um diagnóstico local (fabricante, modelo e versão do Android) com opção para copiar os dados. Ela não solicita permissões especiais. Conexão, leitura de notificações/mensagens, captura de tela e operação remota serão implementadas separadamente, com indicação clara do que será compartilhado e confirmação no próprio aparelho. Permissões especiais do Android, como captura de tela e Acessibilidade, não podem ser concedidas silenciosamente pelo app.

O Android usa a mesma identidade do JMD desktop: fundo `#17191F`, cartões `#22252D`, verde `#B4F36A` e textos `#F0F1F4`/`#969BA7`.

## Build

O JDK 17 e Gradle 8.11.1 usados neste ambiente WSL ficam em `.tools/` (não são enviados ao Git). Para ativá-los no terminal:

```sh
source scripts/use-local-jdk.sh
```

Uma compilação local também precisa do Android SDK 35. O workflow do GitHub configura esse SDK automaticamente e gera o APK:

```sh
cd android-app
gradle assembleDebug
```

O APK de depuração será gerado em `android-app/app/build/outputs/apk/debug/app-debug.apk`.

Depois que o código estiver enviado para `main`, o workflow do GitHub Actions compila o APK e publica o artefato `jmdroid-debug-apk` por 14 dias. Em uma versão marcada `v*`, também cria uma release pública com o arquivo `jmdroid.apk`. A URL estável para baixar a versão mais recente é [github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk](https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk); ela passa a funcionar após a primeira release. No POCO X3, abra esse link, baixe o APK e toque no arquivo baixado para instalar; o Android pode pedir que você permita instalações pelo navegador. O APK debug é para testes; uma distribuição permanente precisará de uma chave de assinatura de release.
