import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

dayjs.extend(utc);
dayjs.extend(timezone);

export const BEIJING_TIMEZONE = 'Asia/Shanghai';

const HAS_TIMEZONE_SUFFIX = /(?:Z|[+-]\d{2}:?\d{2})$/i;

export const beijingTime = (date?: string | Date) => {
  if (!date) {
    return dayjs().tz(BEIJING_TIMEZONE);
  }
  if (typeof date === 'string' && !HAS_TIMEZONE_SUFFIX.test(date)) {
    return dayjs.tz(date, BEIJING_TIMEZONE);
  }
  return dayjs(date).tz(BEIJING_TIMEZONE);
};

export const nowInBeijing = (): string => {
  return beijingTime().format('YYYY-MM-DD HH:mm:ss');
};

/**
 * 格式化为百分比 "32.5%"
 */
export const formatPercent = (value: number): string => {
  return `${(value * 100).toFixed(1)}%`;
};

/**
 * 格式化金额 "¥1,234,567.89"
 */
export const formatCurrency = (value: number): string => {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
};

/**
 * 格式化日期 "2024-03-15 14:30"
 */
export const formatDate = (date: string | Date): string => {
  return beijingTime(date).format('YYYY-MM-DD HH:mm');
};

export const formatDateTime = (date: string | Date): string => {
  return beijingTime(date).format('YYYY-MM-DD HH:mm:ss');
};

/**
 * 截断文本
 */
export const truncateText = (text: string, maxLen: number): string => {
  if (text.length <= maxLen) return text;
  return text.slice(0, maxLen) + '...';
};
