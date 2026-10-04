export const APP_NAME = '智能数据报表系统';

export const CHART_COLORS = {
  primary: '#1677ff',
  success: '#52c41a',
  warning: '#faad14',
  danger: '#ff4d4f',
  series: ['#1677ff', '#52c41a', '#faad14', '#ff4d4f', '#722ed1', '#13c2c2'],
};

export const CONFIDENCE_THRESHOLD = {
  high: 0.8, // 绿色
  medium: 0.6, // 橙色
  // < 0.6 红色
};

export const ORG_LEVELS = {
  PARENT_COMPANY: 1,
  SUBSIDIARY: 2,
  DEPARTMENT: 3,
} as const;
