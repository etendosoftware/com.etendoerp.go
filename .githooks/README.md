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
| `commit-msg` | con el mensaje ya final | Agrega el trailer `Hooks-Verified: v1 <versión> <huella>` |
| `pre-push` | antes del push | Corre JUnit + Sonar y **verifica el trailer** de cada commit que se sube |

## El trailer `Hooks-Verified`

Se agrega automáticamente al final del mensaje del commit:

```
ETP-1234: mi cambio

Hooks-Verified: v1 611c0203 d4768be435ef
```

La **huella se deriva del árbol (tree) del commit**, no es un texto fijo. Por eso:

- No se puede copiar de otro commit (el árbol es distinto → no valida).
- Si hacés `--amend` cambiando contenido, se vuelve a sellar con la huella nueva.
- Los **merge commits** no lo llevan (no los produce un `pre-commit`) y no se exigen.

## Si el push te rechaza commits

Significa que esos commits se crearon **sin los hooks activos**. Para arreglarlo:

```bash
git config core.hooksPath .githooks                        # activar hooks
git rebase --exec 'git commit --amend --no-edit' <base>    # re-sellar tus commits
git push --force-with-lease                                # solo en tu rama
```

Bypass de emergencia (desaconsejado, y el CI igual lo va a marcar):

```bash
HOOKS_VERIFY_SKIP=1 git push
```

## Por qué existe esto

Los hooks corren validaciones locales (tests, Sonar, pipeline) antes de que el
código llegue al PR. Si alguien no los tiene instalados, esas validaciones nunca
corren y nadie se entera. El trailer deja **evidencia visible en GitHub** de que
corrieron, y el check de CI del PR lo hace obligatorio.
