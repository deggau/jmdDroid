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

O workflow do GitHub Actions compila um APK de depuração para testes. Em uma versão marcada `v*`, cria uma release pública com `jmdroid.apk`. A URL estável para baixar a versão mais recente é [github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk](https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk).

O app consulta a release mais recente ao abrir e também oferece o botão **Verificar atualizações**. Quando encontra uma versão nova, baixa o APK, confere o SHA-256 publicado pelo GitHub e abre o instalador do Android. O Android exige confirmação da instalação e pode pedir que você permita instalações desta fonte; o app não consegue instalar atualizações silenciosamente.

Para conectar ao JMD desktop, abra **Configurações > Android > Conectar** no computador e, no JMDroid, toque em **Ler QR code do JMD**. Autorize o uso da câmera e aponte para o código; o app valida o certificado HTTPS pela impressão digital no QR, guarda o token cifrado pelo Android Keystore, mantém uma sessão autenticada e permite listar/executar comandos remotos habilitados no JMD. Celular e computador precisam estar na mesma rede local.

As releases precisam manter a mesma chave de assinatura. O workflow usa uma chave privada fora do repositório, nos segredos `JMDROID_SIGNING_KEYSTORE_B64` e `JMDROID_SIGNING_PASSWORD` do GitHub. Não substitua nem descarte essa chave: sem ela, versões futuras não poderão atualizar por cima das instalações existentes. A versão `0.2.0` anterior foi assinada com uma chave temporária de depuração; quem já a instalou precisará removê-la antes da primeira instalação da versão `0.3.0` assinada com a chave permanente. Depois dessa troca inicial, as próximas versões poderão ser instaladas pelo fluxo dentro do app.
