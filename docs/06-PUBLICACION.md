# 06 — Publicación

**Proyecto:** SonoRitmo
**Versión:** 1.0.0
**Fecha:** 12 de agosto de 2026

---

## 1. Contenido del repositorio

```
sonoritmo/
├── .github/workflows/ci.yml     4 trabajos: static, selfcheck, unit, build
├── app/                         Ensamblaje, receivers, tile, widget, DI raíz
├── core/domain/                 Kotlin puro: toda la lógica que decide algo
├── core/data/                   Room, DataStore, repositorios, backup JSON
├── core/system/                 Audio, DND, alarmas, watchdog, diagnóstico
├── core/ui/                     Tema Material 3 y componentes comunes
├── feature/profiles/            Lista y editor de perfiles
├── feature/tools/               Historial, ajustes, diagnóstico, backup
├── tools/selfcheck/             Arnés de pruebas sin dependencias
├── docs/                        Memoria completa del proyecto, fases 00–06
├── README.md · CONTRIBUTING.md · CHANGELOG.md · LICENSE (GPLv3)
└── gradlew · gradle/wrapper/    Wrapper 8.14.3 commiteado
```

---

## 2. Criterios de aceptación de la v1.0

| # | Criterio | Estado |
|---|---|---|
| 1 | El proyecto compila en un runner limpio | **Pendiente del primer push.** Es el único criterio que no se puede verificar aquí |
| 2 | `:core:domain` compila con `kotlinc` en local | ✅ Verificado |
| 3 | Cobertura de ramas en `:core:domain` | ⚠️ Puerta al 40 %; objetivo del 90 % documentado |
| 4 | Los casos límite críticos tienen prueba y pasan | ✅ 107 comprobaciones + suite JUnit |
| 5 | El manifiesto no contiene `INTERNET` | ✅ Verificado por CI |
| 6 | Exportación → importación sin pérdida | ✅ Test de ida y vuelta en `:core:data` |
| 7 | README, LICENSE, CONTRIBUTING, CHANGELOG y CI | ✅ |
| 8 | `docs/00`…`06` completos | ✅ Verificado por CI |
| 9 | Ninguna decisión temporal en `:core:system` | ✅ Verificado en la auditoría |
| 10 | Repositorio publicado con etiqueta `v1.0.0` | Pendiente del push |

---

## 3. Historial de commits

Los cambios se agrupan por fase, en el orden en que ocurrieron, para que el historial se
lea como la memoria del proyecto y no como un volcado:

1. `docs: investigación y especificaciones de la v1`
2. `docs: revisión del equipo y plan de desarrollo`
3. `build: esqueleto Gradle de siete módulos y catálogo de versiones`
4. `feat(domain): núcleo puro de programación y resolución de conflictos`
5. `feat(data): persistencia Room, repositorios y copia de seguridad JSON`
6. `feat(system): audio, No molestar, alarmas y vigilante`
7. `feat(ui): sistema de diseño y pantallas`
8. `feat(app): ensamblaje, receivers, tile y widget`
9. `ci: pipeline, licencia y documentación del proyecto`
10. `fix: correcciones de la auditoría de QA`

---

## 4. Qué esperar del primer CI

Honestamente: **es probable que el primer build no salga verde a la primera.** El proyecto
se escribió sin poder compilarlo. La auditoría encontró cuatro errores de compilación, y
sería optimista suponer que no queda ninguno.

Lo que sí debería estar bien, porque se ha verificado de verdad:

- La lógica de programación, incluida la aritmética de horario de verano (107 comprobaciones
  ejecutadas).
- Las versiones de todas las dependencias (comprobadas artefacto por artefacto).
- La sintaxis del workflow (validada con un parser de YAML).
- Los recursos y las traducciones (auditados uno a uno).
- Las decisiones de arquitectura (verificadas contra el código, decisión por decisión).

Si algo falla, lo esperable son detalles de tipos en fronteras entre módulos, no problemas
de diseño.

---

## 5. Trabajo pendiente

### v1.0.x — estabilización

1. Poner el CI en verde.
2. Elevar la cobertura de `:core:domain` del 40 % hacia el 90 %.
3. Reactivar `allWarningsAsErrors` y quitar
   `configuration-cache.problems=warn` una vez haya un build de referencia.

### v1.1 — lo que se recortó conscientemente

| Tema | Origen |
|---|---|
| Direct Boot completo: plan mínimo en almacenamiento protegido por dispositivo | `04-DESARROLLO.md` §9 |
| Onboarding guiado de permisos, en lugar del diagnóstico accionable | Recorte de F4 |
| Línea de tiempo semanal (RF-18) | Recorte de F4 |
| Excepciones por fecha (RF-19), con la migración v1→v2 ya prevista | Documento 02, §8 |
| `ZenDeviceEffects` (escala de grises, atenuar fondo) para la plantilla Noche | Documento 02, §8 |
| Traducción de `standbyBucket` y `vendor` en el diagnóstico | `05-QA.md` M4 |
| Migración a AGP 9 + Room 3, en un PR aislado | `04-DESARROLLO.md` §2 |
| Tests instrumentados de la capa de sistema | `05-QA.md` §9 |

### v1.2 y más allá

- Disparadores por WiFi, Bluetooth y ubicación (la petición más repetida en las reseñas de
  la competencia).
- Baseline Profile y macrobenchmark.
- Más idiomas.

---

## 6. Antes de publicar en Google Play

Nada de esto bloquea el repositorio, pero sí la ficha de la tienda:

1. **Verificar el nombre y el `applicationId`.** `com.sonoritmo.app` es irreversible tras la
   primera publicación, y «SonoRitmo» no se ha comprobado frente a marcas registradas ni
   frente a apps existentes.
2. **Justificar `SCHEDULE_EXACT_ALARM`** en la ficha. No se usa `USE_EXACT_ALARM` porque la
   política reserva ese permiso a apps de calendario y despertador.
3. **Justificar el tipo `specialUse`** del servicio en primer plano. La cadena que leerán los
   revisores está en `@string/fgs_special_use_subtype`.
4. **Declarar la seguridad de los datos**: no se recogen datos, no se comparten, no hay red.
   Es la casilla más fácil de rellenar de toda la ficha, y el mayor diferenciador frente a
   dos de las tres apps analizadas.
5. **Confirmar la postura GPLv3 + Play**: es viable y hay precedentes, pero conviene fijar
   cómo se ofrece el código correspondiente a la versión firmada antes de aceptar
   contribuciones externas.
6. **Presupuesto de wakelock** (< 5 min/día) medido en Android Vitals antes de promocionar,
   por la política de batería de Play vigente desde marzo de 2026.

---

## 7. Cierre

El proyecto entrega lo que se propuso: una app cuya promesa central no es tener más
funciones que la competencia, sino **dispararse**. Esa promesa está sostenida por una
decisión concreta —toda la lógica que decide algo vive en un módulo puro, verificable sin
un emulador— y por un motor que trata la programación exacta como el problema de ingeniería
que es.

La documentación de las siete fases se conserva completa en `docs/`, incluidos los seis
informes de revisión sin resumir, en `docs/revisiones/`. La parte más útil de esa memoria no
son las decisiones acertadas: son las cinco que hubo que corregir antes de escribir código,
y las once que hubo que corregir después.
