# Publicación en Google Play

Todo lo que hay que pegar en la Play Console, escrito para copiar tal cual. Lo que aquí no
está resuelto lleva la marca **DECISIÓN PENDIENTE** y no lo puede cerrar nadie más que el
propietario de la cuenta.

---

## 0. Lo que tiene que pasar antes, en orden

1. **DECISIÓN PENDIENTE — el `applicationId`.** Ahora mismo es `com.sonoritmo.app`. En cuanto
   se publique la primera versión, ese identificador es **permanente**: cambiarlo obliga a
   crear una ficha nueva, con cero instalaciones y cero reseñas, y la anterior queda como una
   app distinta. El repositorio se llama Sonify y la app SonoRitmo. Conviven sin problema,
   pero si alguna vez se van a unificar, es **ahora o nunca**.
2. Crear la cuenta de desarrollador en https://play.google.com/console (**25 USD**, pago
   único, verificación de identidad; para cuentas personales creadas desde 2023 Google
   además exige **12 probadores durante 14 días** antes de poder publicar en producción).
3. Subir la política de privacidad a una URL pública (ver §4).
4. Generar el `.aab` firmado (ver §1).

---

## 1. Generar el paquete que Play acepta

Play no admite `.apk` desde agosto de 2021. Hay que subir un **Android App Bundle**:

```bash
gh workflow run apk.yml --ref main -f variant=release -f bundle=true -f publish=false
```

El `.aab` sale como artefacto `sonoritmo-bundle` de la ejecución. No se publica como release
público a propósito: no es instalable en un teléfono y lo único que puede hacer algo con él
es la Play Console.

### Firma

El bundle va firmado con la clave del proyecto (`CN=SonoRitmo, O=SonoRitmo, C=ES`), la misma
que los APK desde la v1.2.0. En Play esa clave pasa a ser la **clave de subida**, y Google
firma la app distribuida con una clave propia (Play App Signing). Consecuencia práctica: si
la clave de subida se pierde, Google puede emitir una nueva; lo que no se puede perder es el
acceso a la cuenta.

---

## 2. Ficha de la tienda

### Nombre de la app (30 caracteres máx.)

```
SonoRitmo
```

### Descripción breve (80 caracteres máx.)

**Español**
```
Perfiles de sonido por horario. Sin internet, sin cuentas, sin anuncios.
```

**Inglés**
```
Scheduled sound profiles. No internet, no accounts, no ads.
```

### Descripción completa (4000 caracteres máx.)

**Español**
```
SonoRitmo cambia el sonido de tu teléfono a la hora que tú decidas y lo devuelve a su sitio
cuando termina. Silencio por la noche, timbre bajo en la oficina, No molestar en las
reuniones: lo configuras una vez y deja de ser tu problema.

QUÉ HACE

• Perfiles completos: los seis volúmenes que Android permite cambiar, el modo de timbre y
  No molestar, todo en un mismo sitio.
• Franjas horarias por día de la semana, con cruce de medianoche resuelto de verdad: una
  franja de 23:00 a 07:00 es una sola franja, no dos.
• Restauración automática: cuando la franja acaba, tus ajustes anteriores vuelven como
  estaban.
• Prioridades: si dos franjas se solapan, gana la que tú digas, y siempre la misma.
• Activación manual con duración: «modo cine durante dos horas» y se apaga solo.
• Pausa global cuando no quieres que nada se mueva.
• Mosaico de Ajustes Rápidos y widget de pantalla de inicio.
• Historial de lo que ha hecho la app, con el motivo de cada cambio y si funcionó.
• Diagnóstico honesto: si a Android le falta un permiso para cumplir lo que has pedido, la
  app te lo dice en la pantalla principal en vez de fallar en silencio.
• Copia de seguridad y restauración en un archivo JSON legible.
• Español e inglés, tema claro y oscuro, y color dinámico.

PRIVACIDAD, Y CÓMO COMPROBARLA

SonoRitmo no pide el permiso de INTERNET. No es una promesa: es una restricción que impone
el propio Android, y significa que la aplicación no puede abrir ninguna conexión de red
aunque quisiera. Sin servidores, sin cuentas, sin anuncios y sin analítica.

El código es libre, con licencia GPLv3, y está publicado entero en GitHub. Su integración
continua rechaza automáticamente cualquier cambio que intente añadir el permiso de INTERNET.
Puedes comprobarlo tú mismo sobre el archivo instalado.

LO QUE ANDROID NO DEJA HACER, Y AQUÍ SE DICE

• La app puede activar No molestar, pero ninguna aplicación puede desactivar un modo que ha
  puesto otra. Es una limitación de Android.
• El volumen de accesibilidad es de solo lectura para las aplicaciones normales.
• Android no permite el volumen por aplicación.
• Si el sistema o tú fuerzan la detención de la app, la automatización se para hasta que
  vuelvas a abrirla. Se detecta y se informa, pero no se puede evitar.

Código fuente: https://github.com/Kelryn/Sonify
```

**Inglés**
```
SonoRitmo changes your phone's sound at the times you choose, and puts it back when the
schedule ends. Silence at night, a quiet ringer at the office, Do Not Disturb during
meetings: set it once and stop thinking about it.

WHAT IT DOES

• Complete profiles: the six volumes Android lets an app change, the ringer mode and Do Not
  Disturb, all in one place.
• Schedules per weekday, with midnight crossing handled properly: 23:00 to 07:00 is one
  window, not two.
• Automatic restore: when a window ends, your previous settings come back as they were.
• Priorities: when two windows overlap, the one you chose wins, every time.
• Manual activation with a duration: "cinema for two hours", then it turns itself off.
• A global pause for when you want nothing to move.
• Quick Settings tile and home screen widget.
• A history of what the app did, why, and whether it worked.
• Honest diagnostics: if Android is missing a permission it needs, the app says so on the
  main screen instead of failing quietly.
• Backup and restore through a readable JSON file.
• English and Spanish, light and dark themes, dynamic colour.

PRIVACY, AND HOW TO CHECK IT

SonoRitmo does not request the INTERNET permission. That is not a promise: it is a
restriction enforced by Android itself, and it means the app cannot open a network
connection even if it wanted to. No servers, no accounts, no ads, no analytics.

The code is free software under the GPLv3 and is published in full on GitHub. Its continuous
integration automatically rejects any change that tries to add the INTERNET permission. You
can verify it yourself against the installed file.

WHAT ANDROID WILL NOT ALLOW, STATED UP FRONT

• The app can turn Do Not Disturb on, but no app can turn off a mode another app set. That
  is an Android limitation.
• Accessibility volume is read-only for ordinary apps.
• Android has no per-app volume.
• If you or the system force-stop the app, automation stops until you open it again. This is
  detected and reported, but it cannot be prevented.

Source code: https://github.com/Kelryn/Sonify
```

### Categoría

`Herramientas` (Tools). Etiquetas sugeridas: productividad, personalización, No molestar.

### Material gráfico obligatorio

| Recurso | Requisito | Estado |
|---|---|---|
| Icono | 512 × 512 PNG, 32 bits, sin transparencia | **PENDIENTE** — hay que exportarlo desde `app/src/main/res/mipmap-anydpi-v26/` |
| Gráfico destacado | 1024 × 500 PNG o JPG | **PENDIENTE** |
| Capturas de teléfono | mínimo 2, máximo 8; entre 320 y 3840 px de lado | Generadas desde el emulador, ver `docs/store/` |

---

## 3. Formulario de seguridad de los datos

Es el más fácil de rellenar de toda la ficha, y el mayor diferenciador frente a la
competencia. Respuestas:

| Pregunta | Respuesta |
|---|---|
| ¿La app recoge o comparte alguno de los tipos de datos obligatorios? | **No** |
| ¿Se cifran los datos en tránsito? | No aplica: no hay tránsito |
| ¿Se pueden solicitar la eliminación de los datos? | No aplica: no se recoge nada. Desinstalar borra todo |
| ¿La app usa un identificador de publicidad? | **No** |
| ¿Hay recogida de datos por librerías de terceros? | **No**. Las dependencias son AndroidX, Room, Hilt y kotlinx; ninguna con telemetría |

Justificación técnica que se puede aportar si Google la pide: el manifiesto no declara
`INTERNET`, verificable con `aapt2 dump permissions`.

---

## 4. Política de privacidad

El texto está en `docs/PRIVACIDAD.md` (español) y `docs/PRIVACY.md` (inglés). Play exige una
**URL pública**. Dos opciones:

- **Rápida**: enlazar el archivo del repositorio,
  `https://github.com/Kelryn/Sonify/blob/main/docs/PRIVACIDAD.md`. Google lo acepta.
- **Presentable**: activar GitHub Pages sobre `docs/` en la configuración del repositorio, lo
  que da `https://kelryn.github.io/Sonify/PRIVACIDAD`.

---

## 5. Clasificación de contenido y público

- Cuestionario IARC: la app no tiene contenido violento, sexual, ni compras, ni contenido
  generado por usuarios, ni comparte ubicación. Resultado esperado: **PEGI 3 / apta para
  todos**.
- Público objetivo: **13 años o más**. No se marca «dirigida a niños», lo que evita entrar en
  el programa Families y sus requisitos adicionales.
- Anuncios: **la app no contiene anuncios**.

---

## 6. Precio

**Gratis**, para empezar. Consecuencia práctica que conviene saber antes: una app publicada
como gratuita **no se puede convertir en de pago después**. El camino habitual si algún día
se quiere monetizar es mantenerla gratis y añadir una compra dentro de la aplicación, lo que
sí requiere cuenta de comerciante y datos fiscales.

Nada de esto hace falta ahora.

---

## 7. Riesgos declarados antes de publicar

- **Ninguna prueba en dispositivo físico.** Toda la verificación de la capa de sistema se ha
  hecho en emulador. El comportamiento de las capas de fabricante (Xiaomi revirtiendo el modo
  de timbre, Samsung acoplando los streams de tono y notificación) **no se reproduce en un
  emulador AOSP** y es exactamente donde esta categoría de aplicaciones falla.
- **Sin tests instrumentados.** Ver `docs/05-QA.md`, riesgo 2.
- `FOREGROUND_SERVICE_SPECIAL_USE` obliga a justificar el subtipo ante los revisores. El texto
  que leerán está en `@string/fgs_special_use_subtype`.
- `SCHEDULE_EXACT_ALARM` hay que justificarlo en la ficha. No se usa `USE_EXACT_ALARM` porque
  la política de Play lo reserva a aplicaciones de calendario y despertador.
