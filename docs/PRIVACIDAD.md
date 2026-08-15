# Política de privacidad de RitMute

**Última actualización: 14 de agosto de 2026**
**Responsable: Kelryn — https://github.com/Kelryn/Sonify**

## Resumen

RitMute **no recoge, no transmite y no comparte ningún dato personal**. No hay servidores,
no hay cuentas, no hay anuncios y no hay analítica.

Esto no es una promesa comercial: la aplicación **no declara el permiso `INTERNET`**, de modo
que el sistema operativo Android le impide abrir cualquier conexión de red, aunque quisiera.
Es una restricción impuesta por el propio Android, no una decisión que dependa de nuestra
buena voluntad, y cualquiera puede comprobarla.

## Cómo comprobarlo por tu cuenta

El código fuente completo es público y está publicado bajo licencia GPLv3 en
https://github.com/Kelryn/Sonify.

La integración continua del proyecto rechaza cualquier cambio que introduzca el permiso
`INTERNET` en el manifiesto de la aplicación. La comprobación está en
`.github/workflows/ci.yml`, bajo el paso «Manifest allow-list», y se ejecuta en cada cambio.

También puedes verificarlo sobre el archivo instalado, sin fiarte de nosotros:

```
aapt2 dump permissions ritmute.apk
```

## Qué datos se guardan y dónde

RitMute guarda **en el propio dispositivo, y únicamente allí**:

- Los perfiles de sonido que creas: nombre, icono, volúmenes, modo de timbre y ajustes de
  No molestar.
- Las franjas horarias que configuras.
- Un historial local de los cambios aplicados, para que puedas ver qué hizo la aplicación y
  cuándo.
- Tus preferencias de la aplicación: tema, idioma y opciones de fiabilidad.
- Una copia de los ajustes de sonido que tenías antes de aplicar un perfil, para poder
  restaurarlos cuando el perfil termina.

Estos datos viven en el almacenamiento privado de la aplicación. Ninguna otra aplicación
puede leerlos. Nunca salen del dispositivo salvo que **tú** exportes una copia de seguridad
a un archivo, en cuyo caso ese archivo queda donde tú decidas guardarlo y bajo tu control.

## Permisos que solicita la aplicación, y para qué

| Permiso | Para qué se usa |
|---|---|
| `ACCESS_NOTIFICATION_POLICY` | Activar y desactivar No molestar, que es la función principal |
| `SCHEDULE_EXACT_ALARM` | Que un cambio programado ocurra a su hora y no quince minutos después |
| `POST_NOTIFICATIONS` | Avisarte cuando se aplica un perfil, si lo activas |
| `RECEIVE_BOOT_COMPLETED` | Reanudar tus horarios después de reiniciar el teléfono |
| `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_SPECIAL_USE` | Aplicar el cambio de sonido de forma fiable, incluso con la pantalla apagada |

Permisos que la aplicación **no** solicita deliberadamente: `INTERNET`, `READ_PHONE_STATE`,
acceso a contactos, ubicación, cámara, micrófono, almacenamiento externo y publicidad.

## Menores

La aplicación no está dirigida específicamente a menores, no recoge datos de nadie y no
muestra contenido generado por terceros.

## Eliminación de tus datos

Desinstalar la aplicación borra todo lo que guarda. No queda ninguna copia en ninguna parte,
porque no existe ninguna parte: nunca se envió nada.

## Cambios en esta política

Si esta política cambia alguna vez, el cambio quedará registrado en el historial público del
repositorio, con su fecha y su motivo.

## Contacto

Abre una incidencia en https://github.com/Kelryn/Sonify/issues
