# Contribuir a RitMute

Gracias por el interés. Unas pocas reglas, todas con motivo.

## Reglas que la CI hace cumplir

1. **`:core:domain` no importa nada de `android.`** Es lo que permite verificar el núcleo
   sin un emulador. Un solo import rompe la compilación, a propósito.
2. **`INTERNET` no aparece en ningún manifiesto.** La promesa de privacidad es comprobable
   o no vale nada.
3. **Toda decisión temporal vive en `:core:domain` y es pura.** `:core:system` son
   adaptadores: si una clase de ahí decide *cuándo* pasa algo, está en el módulo equivocado.
4. **Cero cadenas de texto de usuario en código.** Todo va a `strings.xml`, en español e
   inglés. `MissingTranslation` es un error de lint, no un aviso.

## Antes de abrir un PR

```bash
# El núcleo, sin SDK de Android
kotlinc $(find core/domain/src/main -name '*.kt') tools/selfcheck/DomainSelfCheck.kt \
  -include-runtime -d selfcheck.jar && java -jar selfcheck.jar

# Todo lo demás
./gradlew testDebugUnitTest lintRelease assembleDebug
```

## Estilo

- Código, comentarios, KDoc y mensajes de commit en **inglés**. Documentación de proyecto
  (`docs/`) y textos de usuario, en español e inglés.
- Los comentarios explican **por qué**, no qué. Si el qué no se entiende, el problema es el
  código.
- Un cambio de versión de dependencia va en un PR aislado, sin ningún otro cambio.

## Qué se agradece especialmente

- Informes de campo sobre fabricantes que matan procesos, con modelo y versión.
- Traducciones.
- Casos límite de horario de verano y zonas horarias que rompan `ScheduleWindows`.
