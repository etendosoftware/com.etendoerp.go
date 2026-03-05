# POC: Contract Tests para OpenAPI

Este modulo tenia una suite de tests de contrato como prueba de concepto. Se removieron porque no estan listos para produccion, pero se documenta aqui para poder retomarlo en el futuro.

## Que habia

Tres tests en `src-db/contract/`:

### ContractCoverageTest.java
- Parseaba el spec OpenAPI (`openapi-contracts/index.yaml`) con swagger-parser.
- Verificaba que **toda operacion del contrato** tuviese un `EndpointHandler` registrado en `HandlerRegistry`.
- Verificaba que **todo handler registrado** tuviese una operacion correspondiente en el contrato (sin handlers huerfanos).

### ContractRoundTripTest.java
- Tests de serializacion/deserializacion (Jackson) de los modelos generados por openapi-generator.
- Cubria: `HealthResponse`, `ProductScanRequest`, `ProductScanResponse`, `ErrorResponse`, `BusinessPartner`, `BusinessPartnerListResponse`.

### ContractSchemaTest.java
- Parseaba el spec OpenAPI y para cada schema verificaba que existiese una clase Java generada en `com.etendoerp.etendogo.rest.contract.model`.
- Comprobaba que cada propiedad del schema tuviese su getter correspondiente en la clase.

## Configuracion que tenian en build.gradle

```groovy
sourceSets {
    test {
        java {
            srcDirs 'src-test/src'
        }
    }
}

dependencies {
    testImplementation 'io.swagger.parser.v3:swagger-parser:2.1.13'
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'com.fasterxml.jackson.core:jackson-databind:2.13.2.2'
    testImplementation 'com.fasterxml.jackson.core:jackson-annotations:2.13.2'
}

test {
    useJUnit()
}
```

## Dependencias clave

- `io.swagger.parser.v3:swagger-parser:2.1.13` - para parsear el spec OpenAPI
- `junit:junit:4.13.2`
- Jackson (databind + annotations) para round-trip tests

## Interfaces referenciadas (que deben existir para que CoverageTest funcione)

- `com.etendoerp.etendogo.rest.EndpointHandler` con metodo `getOperationId()`
- `com.etendoerp.etendogo.rest.HandlerRegistry` con metodos `getInstance()` y `getAllHandlers()`

## Para retomar

1. Recrear `src-test/src/` y el sourceSet en build.gradle.
2. Restaurar las dependencias testImplementation.
3. Implementar `EndpointHandler` y `HandlerRegistry` si no existen aun.
4. Colocar los tests en el package `com.etendoerp.etendogo.contract` dentro de src-test.
