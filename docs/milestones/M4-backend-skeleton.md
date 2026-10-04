# M4 Backend Milestone - Spring Boot Skeleton

## Status: COMPLETED

## Deliverables

### Infrastructure Layer

- [x] `pom.xml` with all dependencies (Spring Boot 3.2.5, MyBatis-Plus 3.5.6, JSqlParser 4.9, PostgreSQL, Redis, JWT)
- [x] `application.yml` - base configuration with dual datasource
- [x] `application-local.yml` - local development credentials template
- [x] `application-ex.yml` - external deployment configuration template
- [x] `schema-meta.sql` - PostgreSQL metadata schema with seed data
- [x] `schema-business.sql` - SQL Server business schema reference

### Config Layer

- [x] `BusinessDataSourceConfig.java` - @Primary read-only business DB
- [x] `MetaDataSourceConfig.java` - Read-write metadata DB
- [x] `RedisConfig.java` - Redis template configuration
- [x] `SecurityConfig.java` - JWT authentication chain
- [x] `AsyncConfig.java` - Thread pools (auditExecutor: core=2, max=4, queue=500)
- [x] `LLMConfig.java` - LLM properties (provider, openai, local)

### Common Layer

- [x] `ApiResult.java` - Standardized API response
- [x] `PageResult.java` - Paginated response wrapper
- [x] `ErrorCode.java` - Error codes enum (400001-503001)
- [x] `BusinessException.java` - Custom exception
- [x] `GlobalExceptionHandler.java` - @ControllerAdvice exception handler
- [x] `TraceContext.java` - ThreadLocal request context
- [x] `TraceFilter.java` - Request ID filter with HIGHEST_PRECEDENCE

### Security Layer

- [x] `JwtUtil.java` - JWT token generation/validation
- [x] `JwtAuthFilter.java` - JWT authentication filter

### Domain Layer - Meta Entities

- [x] `OrgTree.java`
- [x] `MetaDatasource.java`
- [x] `MetaTable.java`
- [x] `MetaColumn.java`
- [x] `MetaGlossary.java`
- [x] `MetaJoinEdge.java`
- [x] `MetaSqlExample.java`
- [x] `MetaDdlUpload.java`
- [x] `AuditQueryLog.java`

### Domain Layer - Business Entities

- [x] `DimOrg.java`
- [x] `DimProduct.java`
- [x] `DimChannel.java`
- [x] `FactGrossProfitDetail.java`

### Mapper Layer

- [x] 9 meta mappers (MyBatis-Plus BaseMapper)
- [x] 4 business mappers
- [x] 6 mapper XML files with custom queries

### Service Layer

- [x] `SqlGuardService.java` - JSqlParser AST validation, TOP injection, blocked keyword detection
- [x] `AuditService.java` - Async audit logging via @Async("auditExecutor")
- [x] `MetadataService.java` - Placeholder for metadata retrieval
- [x] `OrgService.java` - Placeholder for org tree operations
- [x] `QueryExecutorService.java` - Placeholder for SQL execution
- [x] `OrchestratorService.java` - Placeholder for end-to-end orchestration
- [x] `DDLParserService.java` - Placeholder for DDL parsing
- [x] `AuthService.java` - Login/logout with demo credentials

### LLM Layer

- [x] `LLMService.java` - Interface (Rule 6: interface-driven)
- [x] `OpenAILLMService.java` - OpenAI implementation (throws NotImplementedYet)
- [x] `LocalLLMService.java` - Local LLM placeholder (throws NotImplementedYet)

### Controller Layer

- [x] `AuthController.java` - /api/v1/auth/** endpoints
- [x] `HealthController.java` - /api/v1/health endpoints with component checks
- [x] `QueryController.java` - /api/v1/query endpoint
- [x] `MetadataController.java` - /api/v1/metadata/** endpoints
- [x] `OrgController.java` - /api/v1/orgs/** endpoints

### Tests

- [x] `SqlGuardServiceTest.java` - 15 test cases covering:
  - Valid SELECT statements (7 tests)
  - Blocked statements (7 tests)
  - Blocked keywords/procedures (3 tests)
  - Edge cases (4 tests)
  - LIMIT to TOP conversion (3 tests)

## Architecture Compliance

| Rule | Status |
|------|--------|
| Rule 1: Metadata-only prompting | N/A (M5 scope) |
| Rule 2: Query gateway mandatory | N/A (M5 scope) |
| Rule 3: AST-based SQL safety | ✅ Implemented in SqlGuardService |
| Rule 4: Metadata improvement order | N/A (M5 scope) |
| Rule 5: Structured LLM outputs | N/A (M5 scope) |
| Rule 6: LLM interface-driven | ✅ LLMService interface + 2 implementations |
| Rule 7: DDL upload pipeline | Partial (placeholder, M5 implementation) |

## Next Steps (M5)

1. Implement MetadataService with actual PostgreSQL queries
2. Implement OrgService with org tree resolution
3. Implement DDLParserService with SQL Server DDL parsing
4. Implement OpenAILLMService with actual API calls
5. Wire up OrchestratorService end-to-end flow
6. Create prompt templates in `/prompts/`
7. Frontend integration

## Notes

- All services throw `BusinessException(ErrorCode.INTERNAL_ERROR, "Not implemented yet")` as placeholders
- Demo credentials: admin/admin123, demo/demo123, user/user123
- JWT blacklist stored in Redis
- SqlGuardService uses JSqlParser 4.9 for AST parsing
- TOP 1000 limit auto-injected for queries without TOP clause
