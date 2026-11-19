# JMD para Android

Aplicativo Android nativo em Kotlin para conectar ao JMD executado no computador.

## Recursos

A tela de diagnóstico mostra fabricante, modelo e versão do Android e permite copiar esses dados. O app também pareia com o JMD desktop após aprovação no computador, mantém uma sessão autenticada, lista os comandos remotos disponíveis e permite enviar texto para execução e copiar o resultado.

Para parear, abra **Configurações > Android > Conectar** no JMD desktop e toque em **Ler QR code do JMD** no app. Ao autorizar a câmera, o JMDroid lê o código no próprio aparelho e aguarda a aprovação explícita no desktop. O QR pode apontar para HTTPS (com validação da impressão digital do certificado) ou HTTP em IPv4 privado. Antes de usar HTTP, o app pede confirmação e avisa que o segredo de pareamento e o token serão enviados sem criptografia; use apenas em rede interna confiável e não exponha a porta do JMD à internet. O token é protegido no aparelho por uma chave do Android Keystore. Também há a opção de colar manualmente o conteúdo JSON.

O JMDroid pode manter pareamentos com vários computadores. A tela inicial mostra o estado online/offline de cada um, permite alternar o computador ativo e remover pareamentos com um toque longo. Comandos, tarefas e compartilhamento usam o computador ativo.

Durante o compartilhamento da tela do celular, o desktop pode enviar cliques e rolagens. Para isso, o Android exige que a pessoa habilite manualmente o serviço Controle remoto do JMDroid em Acessibilidade; o app oferece um atalho para abrir essa tela. A captura de tela também pede o consentimento próprio do Android.

## Gerar APK

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e selecione **Build > Build APK(s)**. Também é possível compilar pela linha de comando em um ambiente com JDK 17, Android SDK 35 e Gradle 8.11.1:

```sh
gradle assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Também há uma URL de download na release pública mais recente após a primeira versão ser publicada: <https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk>. No POCO X3, baixe o arquivo e toque nele para instalar; o Android pode pedir permissão para instalar apps pelo navegador.

O workflow no GitHub Actions também publica o APK debug como artefato de cada execução.
