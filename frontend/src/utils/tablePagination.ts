const QUICK_JUMPER_PAGE_THRESHOLD = 7;

interface CompactTablePaginationOptions {
  current: number;
  pageSize: number;
  total: number;
  onChange: (page: number, pageSize: number) => void;
  showTotal?: (total: number, range?: [number, number]) => string;
  pageSizeOptions?: string[];
  hideOnSinglePage?: boolean;
  showSizeChanger?: boolean;
  showLessItems?: boolean;
  responsive?: boolean;
  showQuickJumper?: boolean;
}

function normalizeTotal(total: number): number {
  return Number.isFinite(total) ? Math.max(0, total) : 0;
}

function shouldHideOnSinglePage(total: number, pageSize: number): boolean {
  return total > 0 && pageSize > 0 && total <= pageSize;
}

export function shouldEnableQuickJumper(total = 0, pageSize = 10): boolean {
  if (pageSize <= 0 || total <= 0) {
    return false;
  }

  return Math.ceil(total / pageSize) > QUICK_JUMPER_PAGE_THRESHOLD;
}

export function buildCompactTablePagination({
  current,
  pageSize,
  total,
  onChange,
  showTotal,
  pageSizeOptions = ['10', '20', '50', '100'],
  hideOnSinglePage,
  showSizeChanger = true,
  showLessItems = true,
  responsive = true,
  showQuickJumper,
}: CompactTablePaginationOptions) {
  const normalizedTotal = normalizeTotal(total);

  return {
    current,
    pageSize,
    total: normalizedTotal,
    showSizeChanger,
    showLessItems,
    responsive,
    hideOnSinglePage: hideOnSinglePage ?? shouldHideOnSinglePage(normalizedTotal, pageSize),
    showQuickJumper: showQuickJumper ?? shouldEnableQuickJumper(normalizedTotal, pageSize),
    pageSizeOptions,
    showTotal,
    onChange,
  };
}
