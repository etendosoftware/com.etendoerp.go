# Git hooks — com.etendoerp.go

## Instalación (una sola vez, por clon del repo)

```bash
git config core.hooksPath .githooks
```

Verificá que quedó activo:

```bash
git config --get core.hooksPath     # debe imprimir: .githooks
```

> Este repo no usa npm, así que no hay un `postinstall` que lo configure solo:
> **hay que correr ese comando a mano en cada clon**. Si no lo hacés, tus commits
> quedan sin el sello `Hooks-Verified` y serán rechazados al hacer push y por el
> check de CI del PR.

## Qué hace cada hook

| Hook | Cuándo | Qué hace |
|------|--------|----------|
| `pre-commit` | antes de crear el commit | Chequeos rápidos (marcadores de conflicto) y **registra la prueba** de que los hooks corrieron |
| `commit-msg` | con el mensaje ya final | Valida el formato del mensaje que exige Git Police (`Feature ETP-1234: …`, `Issue #42: …` con el ticket en el cuerpo, primera línea de hasta 80 caracteres) y agrega el trailer `Hooks-Verified` |
| `post-rewrite` | después de un `--amend` o un `rebase` | Revisa los commits reescritos y **avisa** si alguno quedó con el trailer `Hooks-Verified` vencido, con el comando para volver a sellarlo. No bloquea |
| `pre-push` | antes del push | Corre XML check + Sonar (issues, modo branch) + JUnit sin base + Sonar (gate, modo PR) + cobertura de las líneas cambiadas y **verifica el trailer** de cada commit que se sube |

### Pasos de `pre-push`

| # | Paso | Script | Por qué |
|---|------|--------|---------|
| 0 | Convenciones: nombre de la rama contra su destino, división unit/integración y Test Hygiene | `lib/git-conventions.sh`, `scripts/check-unit-test-classification.sh`, `scripts/check-test-hygiene.py` | Adelanta lo que rechazarían Git Police, Unit Test Classification y Test Hygiene en el PR. Una rama que `main` no acepta bloquea; un nombre fuera de convención solo avisa. Los chequeos de tests corren si el push toca tests, `build.gradle` o los scripts. CI los mantiene: los hooks se pueden saltear |
| 1 | Validar orden/constraints de los XML de `sourcedata` | `check-etgo-xml.sh` | Espeja el check de CI `xml-order-check.yml`; barato y falla rápido |
| 1b | Sonar — issues, **modo branch** | `check-sonar-issues.sh` | El paso 3 analiza en modo PR, y ahí `sonar.java.binaries=.` apunta a un directorio sin `.class`, así que ECJ no resuelve tipos y el JavaSensor reporta 0 issues **siempre**. El modo branch (`-Dsonar.branch.name=...`) hace un parseo completo y encuentra issues reales. Bloquea solo si el issue cae en una línea que este push agregó o modificó |
| 2 | Tests sin base de datos (`goTestUnit`) + su reporte JaCoCo | `./gradlew goTestUnit goTestUnitCoverageReport` en el core | Corre todo lo que no necesita Postgres ni Tomcat (ETP-5695). Los `*IntegrationTest` con base corren en la suite completa de CI, no acá |
| 3 | Sonar (gate), **modo PR**, con la cobertura de `goTestUnit` | `run-sonar.sh --fail-on-gate` | Espeja el Quality Gate de CI. Ya no compara la cobertura global contra la base: sin los tests con base siempre baja; esa comparación queda en CI |
| 3b | Cobertura de las líneas cambiadas | `scripts/check-diff-coverage.py` | Mide qué parte de las líneas que agrega o modifica el push cubre `goTestUnit` (umbral 80%, `DIFF_COVERAGE_THRESHOLD`). Por ahora solo avisa; `DIFF_COVERAGE_MODE=block` lo hace bloqueante. Se saltea si el paso 2 falló |

Antes del paso 1, el hook resuelve la **rama destino** del push (`lib/target-branch.sh`, igual que
en etendo_schema_forge): `PRE_PUSH_TARGET` si está definida, si no la base del PR abierto de la rama
(vía `gh`), si no `main` para `main`, `hotfix/*` y el corte (`mergeblock/*`), y `develop` para el resto. Contra esa rama chequea
conflictos (`git merge-tree`) y mide el diff de relevancia, Sonar y la cobertura de líneas cambiadas.

El paso 1b nunca reemplaza al 3: el 3 sigue siendo la fuente de verdad para el
Quality Gate (server-side, modo PR); el 1b solo tapa el agujero
estructural del modo PR para issues (0 siempre) corriendo un análisis paralelo
en modo branch. Ninguno de los dos se cachea — su veredicto depende del
servidor Sonar, no solo del árbol de trabajo.

## El trailer `Hooks-Verified`

Se agrega automáticamente al final del mensaje del commit:

```
ETP-1234: mi cambio

Hooks-Verified: v1 611c0203 d4768be435ef
```

La **huella se deriva del árbol (tree) del commit**, no es un texto fijo. Por eso:

- No se puede copiar de otro commit (el árbol es distinto → no valida).
- Si hacés `--amend` cambiando contenido, se vuelve a sellar con la huella nueva.
- Un `rebase` no vuelve a sellar. Si al resolver un conflicto cambia el diff de un commit, su sello
  queda vencido; `post-rewrite` te lo avisa en ese momento. Un rebase sin conflictos conserva el sello.
- Los **merge commits** no lo llevan (no los produce un `pre-commit`) y no se exigen.

## Si el push te rechaza commits

Significa que esos commits se crearon **sin los hooks activos**, o que se modificaron después:
un `--amend` con `--no-verify`, o un `rebase` que resolvió un conflicto. Para arreglarlo:

```bash
git config core.hooksPath .githooks                        # activar hooks
git rebase --exec 'git commit --amend --no-edit' <base>    # re-sellar tus commits
git push --force-with-lease                                # solo en tu rama
```

No hay atajo: lo que el pre-push frena es lo que hay que arreglar, y el check `verify` del PR
rechaza igual esos commits.

## Por qué existe esto

Los hooks corren validaciones locales (tests, Sonar, pipeline) antes de que el
código llegue al PR. Si alguien no los tiene instalados, esas validaciones nunca
corren y nadie se entera. El trailer deja **evidencia visible en GitHub** de que
corrieron, y el check de CI del PR lo hace obligatorio.
