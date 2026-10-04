import React from 'react';

interface InsightBridgeIconProps {
  size?: number;
  className?: string;
}

export const InsightBridgeIcon: React.FC<InsightBridgeIconProps> = ({
  size = 20,
  className,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    className={className}
  >
    <defs>
      <linearGradient id="insightbridge-icon-gradient" x1="4" y1="4" x2="20" y2="20" gradientUnits="userSpaceOnUse">
        <stop stopColor="#1D4ED8" />
        <stop offset="1" stopColor="#60A5FA" />
      </linearGradient>
    </defs>
    <rect x="2.5" y="2.5" width="19" height="19" rx="6" fill="url(#insightbridge-icon-gradient)" />
    <path
      d="M6.5 15.5C8.1 12.7 9.85 11.3 12 11.3C14.15 11.3 15.9 12.7 17.5 15.5"
      stroke="white"
      strokeWidth="1.9"
      strokeLinecap="round"
    />
    <path
      d="M8.1 15.5V9.4M12 15.5V8.2M15.9 15.5V9.4"
      stroke="white"
      strokeWidth="1.6"
      strokeLinecap="round"
    />
    <path
      d="M6.1 17.3H17.9"
      stroke="#DBEAFE"
      strokeWidth="1.4"
      strokeLinecap="round"
      opacity="0.95"
    />
  </svg>
);

export default InsightBridgeIcon;
