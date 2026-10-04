import { authHandlers } from './authHandlers';
import { queryHandlers } from './queryHandlers';
import { orgHandlers } from './orgHandlers';
import { metadataHandlers } from './metadataHandlers';
import { auditHandlers } from './auditHandlers';
import { userHandlers } from './userHandlers';
import { embeddingHandlers } from './embeddingHandlers';
import { modelConfigHandlers } from './modelConfigHandlers';

export const handlers = [
  ...authHandlers,
  ...queryHandlers,
  ...orgHandlers,
  ...userHandlers,
  ...embeddingHandlers,
  ...modelConfigHandlers,
  ...metadataHandlers,
  ...auditHandlers,
];
