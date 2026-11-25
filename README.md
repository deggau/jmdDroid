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

Ao preparar cada release, incremente `versionCode` (número inteiro, sempre crescente) e atualize `versionName` (versão exibida na tela inicial do app) em `android-app/app/build.gradle.kts`. A tag `vX.Y.Z` precisa corresponder ao `versionName`; o workflow verifica essa correspondência antes de compilar e publicar. A versão atual é `0.6.14` (build `23`), com o novo pareamento por descoberta na rede e identidade RSA.

O app consulta a release mais recente ao abrir e também oferece o botão **Verificar atualizações**. Quando encontra uma versão nova, baixa o APK, confere o SHA-256 publicado pelo GitHub e abre o instalador do Android. O Android exige confirmação da instalação e pode pedir que você permita instalações desta fonte; o app não consegue instalar atualizações silenciosamente.

Para conectar ao JMD desktop, toque em **Conectar ao JMD** e escolha **Buscar computadores na rede**. O app verifica a resposta de descoberta assinada e mostra os computadores encontrados; selecione um e aprove o pedido no JMD. A conexão usa a identidade RSA persistente do Android Keystore e verifica a identidade/certificado do computador. Se o broadcast estiver bloqueado, ainda é possível ler ou colar o QR; ele agora serve como alternativa para iniciar o mesmo pareamento por identidade. HTTP continua disponível apenas com confirmação explícita e aviso de que o tráfego não é criptografado. Celular e computador precisam estar na mesma rede local.

As releases precisam manter a mesma chave de assinatura. O workflow usa uma chave privada fora do repositório, nos segredos `JMDROID_SIGNING_KEYSTORE_B64` e `JMDROID_SIGNING_PASSWORD` do GitHub. Não substitua nem descarte essa chave: sem ela, versões futuras não poderão atualizar por cima das instalações existentes. A versão `0.2.0` anterior foi assinada com uma chave temporária de depuração; quem já a instalou precisará removê-la antes da primeira instalação da versão `0.3.0` assinada com a chave permanente. Depois dessa troca inicial, as próximas versões poderão ser instaladas pelo fluxo dentro do app.
