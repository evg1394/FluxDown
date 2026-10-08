/**
 * GPUI 桌面客户端模拟：复刻 `crates/shell`（统一顶栏 + 活动栏）与 `crates/downloads`
 * （顶栏插槽、侧栏『状态』文件夹内嵌分类子项、无网格线任务表、表头选择条、页内状态栏、新建下载对话框）。
 * 布局与尺寸以源码常量为准：活动栏 48、侧栏默认 200、表头 28、选择列 36、列宽见 `task_table.rs`。
 *
 * 所有颜色 / 尺寸 / 圆角 / 字体 / 阴影都只读根节点上的 CSS 变量（由 resolve 结果生成，
 * 见 `model.ts`），元素挂 `data-token-paths` 供右键检视。macOS 交通灯是系统绘制，不属于主题。
 */
import { useEffect, useState, type CSSProperties, type ReactNode } from "react";
import {
  AppWindow,
  ArrowDown,
  ArrowDownToLine,
  ArrowUp,
  Check,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  CircleArrowDown,
  CircleCheck,
  CirclePause,
  Cpu,
  Disc3,
  Download,
  ExternalLink,
  File,
  FileArchive,
  FileImage,
  FileMusic,
  FilePlay,
  FileText,
  Film,
  Folder,
  FolderOpen,
  Globe,
  HardDrive,
  Image,
  Layers,
  Minus,
  Moon,
  Music,
  Archive,
  PanelLeft,
  Pause,
  Play,
  Plus,
  Power,
  Rows3,
  Rss,
  Search,
  Settings,
  SlidersHorizontal,
  Square,
  Sun,
  Trash2,
  Webhook,
  X,
} from "lucide-react";
import type { ResolvedTokens } from "@/lib/gpui-theme/resolve";
import type { ThemeMode } from "@/lib/gpui-theme/types";
import type { ThemeBuilderMessages } from "@/i18n/messages/themeBuilder";
import { cn } from "@/lib/utils";
import { tokenAttrs } from "../tokens";
import { cssVariables, v } from "./model";
import { withBase } from "@/lib/base";

type PreviewMessages = ThemeBuilderMessages["gpuiMock"];

type Status = "downloading" | "paused" | "failed" | "queued" | "completed";
type Category = "video" | "audio" | "document" | "image" | "program" | "archive" | "other";
type Kind = "video" | "audio" | "document" | "image" | "archive" | "disk" | "app" | "other";
type StatusFolder = "all" | "downloading" | "completed" | "failed" | "paused";
type Section = "status" | "queues" | "devices";
type Icon = typeof File;

interface DemoTask {
  id: string;
  name: string;
  category: Category;
  kind: Kind;
  site: string;
  size: string;
  downloaded: string;
  progress: number;
  speed?: string;
  etaMinutes?: number;
  transfers?: number;
  queuePosition?: number;
  status: Status;
  created: string;
}

const TASKS: DemoTask[] = [
  { id: "1", name: "ubuntu-24.04.1-desktop-amd64.iso", category: "program", kind: "disk", site: "releases.ubuntu.com", size: "5.7 GB", downloaded: "3.5 GB", progress: 0.62, speed: "11.4 MB/s", etaMinutes: 3, transfers: 16, status: "downloading", created: "2026-09-27 10:42:18" },
  { id: "2", name: "Big.Buck.Bunny.2160p.mkv", category: "video", kind: "video", site: "download.blender.org", size: "2.1 GB", downloaded: "752.6 MB", progress: 0.35, speed: "1.4 MB/s", etaMinutes: 16, transfers: 8, status: "downloading", created: "2026-09-27 10:31:05" },
  { id: "3", name: "annual-report-2025.pdf", category: "document", kind: "document", site: "example.com", size: "18.4 MB", downloaded: "8.8 MB", progress: 0.48, status: "paused", created: "2026-09-26 21:07:44" },
  { id: "4", name: "lofi-mix-vol3.flac", category: "audio", kind: "audio", site: "archive.org", size: "412.0 MB", downloaded: "49.4 MB", progress: 0.12, status: "failed", created: "2026-09-26 18:55:12" },
  { id: "5", name: "wallpaper-pack-4k.zip", category: "archive", kind: "archive", site: "unsplash.com", size: "1.3 GB", downloaded: "0 B", progress: 0, queuePosition: 1, status: "queued", created: "2026-09-26 18:40:37" },
  { id: "6", name: "node-v22.9.0-x64.msi", category: "program", kind: "app", site: "nodejs.org", size: "28.9 MB", downloaded: "28.9 MB", progress: 1, status: "completed", created: "2026-09-25 09:12:09" },
  { id: "7", name: "sunset-coast.png", category: "image", kind: "image", site: "images.example.com", size: "6.2 MB", downloaded: "6.2 MB", progress: 1, status: "completed", created: "2026-09-24 16:20:51" },
];

/** 侧栏分类图标（`components/src/icons.rs::category_icon`）。 */
const CATEGORY_ICON: Record<Category, Icon> = {
  video: Film,
  audio: Music,
  document: FileText,
  image: Image,
  program: Cpu,
  archive: Archive,
  other: File,
};

const CATEGORIES: Category[] = ["video", "audio", "document", "image", "program", "archive", "other"];

/** 任务表文件类型回退图标（task_table.rs `kind_icon`）。 */
const KIND_ICON: Record<Kind, Icon> = {
  video: FilePlay,
  audio: FileMusic,
  document: FileText,
  image: FileImage,
  archive: FileArchive,
  disk: Disc3,
  app: AppWindow,
  other: File,
};

/** 状态文字色（task_table.rs `status_color`）。 */
const STATUS_TEXT: Record<Status, string> = {
  downloading: "colors.statusDownloading",
  paused: "colors.statusPaused",
  failed: "colors.statusFailed",
  queued: "colors.statusQueued",
  completed: "colors.statusCompleted",
};

/** 进度条填充色（task_table.rs `progress_bar_color`）；暂停为 statusPaused 的 40%。 */
const PROGRESS_FILL: Record<Status, { path: string; css: string }> = {
  downloading: { path: "colors.progressFill", css: v("colors.progressFill") },
  paused: { path: "colors.statusPaused", css: `color-mix(in srgb, ${v("colors.statusPaused")} 40%, transparent)` },
  failed: { path: "colors.statusFailed", css: v("colors.statusFailed") },
  queued: { path: "colors.statusQueued", css: v("colors.statusQueued") },
  completed: { path: "colors.statusCompleted", css: v("colors.statusCompleted") },
};

const FOLDER_MATCH: Record<StatusFolder, (task: DemoTask) => boolean> = {
  all: () => true,
  downloading: (task) => task.status === "downloading" || task.status === "queued",
  completed: (task) => task.status === "completed",
  failed: (task) => task.status === "failed",
  paused: (task) => task.status === "paused",
};

const FOLDER_ICON: Record<StatusFolder, Icon> = {
  all: Layers,
  downloading: CircleArrowDown,
  completed: CircleCheck,
  failed: CircleAlert,
  paused: CirclePause,
};

const FOLDERS: StatusFolder[] = ["all", "downloading", "completed", "failed", "paused"];

/** shell 活动栏宽（`ACTIVITY_RAIL_WIDTH`）与下载页侧栏默认宽（`ViewPrefs::sidebar_width`）。 */
const ACTIVITY_RAIL_WIDTH = 48;
const SIDEBAR_WIDTH = 200;
/** macOS `TitleBar` 为交通灯预留的左内边距（gpui-component `TITLE_BAR_LEFT_PADDING`）。 */
const MAC_TRAFFIC_LIGHT_WIDTH = 80;
/** Windows / Linux 窗口按钮边长（gpui-component `TITLE_BAR_HEIGHT`）。 */
const WINDOW_CONTROL_WIDTH = 34;
/** 任务表：表头高、选择列、右侧留白（task_table.rs 常量）。 */
const TABLE_HEADER_HEIGHT = 28;
const SELECTION_COLUMN_WIDTH = 36;
const TABLE_TRAILING_GUTTER = 8;
const PROGRESS_LABEL_WIDTH = 32;

/** 文字角色（`typography.<role>.{size,lineHeight,weight}`）。 */
function text(role: string): CSSProperties {
  return {
    fontSize: v(`typography.${role}.size`),
    lineHeight: v(`typography.${role}.lineHeight`),
    fontWeight: v(`typography.${role}.weight`),
  };
}

const hairline = `${v("stroke.thin")} solid ${v("colors.hairline")}`;
const iconSm = { width: v("icon.sm"), height: v("icon.sm") };
const iconMd = { width: v("icon.md"), height: v("icon.md") };
const iconLg = { width: v("icon.lg"), height: v("icon.lg") };

/** task_table.rs `percent_label`：向下取整，0~1% 之间显示 `<1%`。 */
function percentLabel(progress: number): string {
  const percent = Math.min(Math.max(progress, 0), 1) * 100;
  if (percent > 0 && percent < 1) return "<1%";
  return `${Math.floor(percent)}%`;
}

function CheckMark({ checked }: { checked: boolean }) {
  return (
    <span
      className="grid shrink-0 place-items-center"
      style={{
        width: v("density.checkMark"),
        height: v("density.checkMark"),
        borderRadius: v("components.checkbox.radius"),
        backgroundColor: checked ? v("colors.primary") : "transparent",
        border: checked ? "none" : `${v("stroke.thin")} solid color-mix(in srgb, ${v("colors.mutedForeground")} 55%, transparent)`,
        color: v("colors.primaryForeground"),
      }}
      {...tokenAttrs("density.checkMark", "components.checkbox.radius", "colors.primary", "colors.primaryForeground", "colors.mutedForeground")}
    >
      {checked && <Check style={iconSm} strokeWidth={3} />}
    </span>
  );
}

function ProgressBar({ task }: { task: DemoTask }) {
  const fill = PROGRESS_FILL[task.status];
  return (
    <div
      className="min-w-0 flex-1 overflow-hidden"
      style={{ height: v("components.progress.height"), borderRadius: v("components.progress.radius"), backgroundColor: v("colors.progressTrack") }}
      {...tokenAttrs("components.progress.height", "components.progress.radius", "colors.progressTrack", fill.path)}
    >
      <div className="h-full" style={{ width: `${task.progress * 100}%`, backgroundColor: fill.css, borderRadius: v("components.progress.radius") }} />
    </div>
  );
}

/** `Button::control`（components/src/kit.rs）：高 = density.control，左右 sm + xxs，图标与文字间距 8。 */
function Button({
  variant,
  children,
  onClick,
  paths = [],
}: {
  variant: "primary" | "secondary" | "ghost";
  children: ReactNode;
  onClick?: () => void;
  paths?: string[];
}) {
  const colors: Record<typeof variant, { style: CSSProperties; className: string; paths: string[] }> = {
    primary: {
      style: { backgroundColor: v("colors.primary"), color: v("colors.primaryForeground") },
      className: "hover:brightness-110",
      paths: ["colors.primary", "colors.primaryForeground"],
    },
    secondary: {
      style: { backgroundColor: v("colors.secondary"), color: v("colors.secondaryForeground"), border: `${v("stroke.thin")} solid ${v("colors.border")}` },
      className: "hover:brightness-110",
      paths: ["colors.secondary", "colors.secondaryForeground", "colors.border"],
    },
    ghost: {
      style: { color: v("colors.foreground") },
      className: "hover:bg-[var(--gt-colors-secondary)]",
      paths: ["colors.foreground", "colors.secondary"],
    },
  };
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn("inline-flex shrink-0 items-center whitespace-nowrap", colors[variant].className)}
      style={{
        height: v("density.control"),
        paddingInline: `calc(${v("spacing.sm")} + ${v("spacing.xxs")})`,
        gap: v("spacing.sm"),
        borderRadius: v("components.button.radius"),
        ...text("sm"),
        ...colors[variant].style,
      }}
      {...tokenAttrs("components.button.radius", "density.control", "spacing.sm", "spacing.xxs", ...colors[variant].paths, ...paths)}
    >
      {children}
    </button>
  );
}

/** `toolbar_action_button`（components/src/lib.rs）：正方形图标按钮，muted 图标，悬停 navHover。 */
function ToolbarIconButton({
  icon: IconComponent,
  label,
  size = v("density.toolbarButton"),
  iconStyle = iconMd,
  destructive = false,
  onClick,
}: {
  icon: Icon;
  label: string;
  size?: string;
  iconStyle?: CSSProperties;
  destructive?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={cn(
        "grid shrink-0 place-items-center hover:bg-[var(--gt-colors-navHover)]",
        !destructive && "hover:text-[var(--gt-colors-foreground)]",
      )}
      style={{
        width: size,
        height: size,
        borderRadius: v("components.button.radius"),
        color: destructive ? v("colors.destructive") : v("colors.mutedForeground"),
      }}
      {...tokenAttrs("density.toolbarButton", "components.button.radius", "colors.navHover", destructive ? "colors.destructive" : "colors.mutedForeground")}
    >
      <IconComponent style={iconStyle} />
    </button>
  );
}

function Switch({ on }: { on: boolean }) {
  return (
    <span
      className="relative inline-block h-[18px] w-8 shrink-0"
      style={{ borderRadius: v("radius.full"), backgroundColor: on ? v("colors.primary") : v("colors.input") }}
      {...tokenAttrs("colors.primary", "colors.input", "radius.full", "colors.background")}
    >
      <span
        className="absolute top-[2px] h-[14px] w-[14px] transition-[left]"
        style={{ left: on ? 16 : 2, borderRadius: v("radius.full"), backgroundColor: v("colors.background"), boxShadow: v("shadow.sm") }}
      />
    </span>
  );
}

function Badge({ children, tone }: { children: ReactNode; tone: "primary" | "muted" | "success" | "warning" }) {
  const map = {
    primary: ["colors.accent", "colors.accentForeground"],
    muted: ["colors.muted", "colors.mutedForeground"],
    success: ["colors.success", "colors.background"],
    warning: ["colors.warning", "colors.background"],
  } as const;
  const [bg, fg] = map[tone];
  return (
    <span
      className="inline-flex items-center"
      style={{ ...text("caption"), backgroundColor: v(bg), color: v(fg), borderRadius: v("components.badge.radius"), paddingInline: v("spacing.sm") }}
      {...tokenAttrs("components.badge.radius", bg, fg, "typography.caption.size")}
    >
      {children}
    </span>
  );
}

function Input({ value, focused, children }: { value: string; focused?: boolean; children?: ReactNode }) {
  return (
    <div
      className="flex min-w-0 flex-1 items-center"
      style={{
        height: v("density.control"),
        paddingInline: v("spacing.sm"),
        gap: v("spacing.xs"),
        borderRadius: v("components.input.radius"),
        backgroundColor: v("colors.background"),
        border: `${v("stroke.thin")} solid ${focused ? v("colors.ring") : v("colors.input")}`,
        boxShadow: focused ? `0 0 0 ${v("focusRing.width")} color-mix(in srgb, ${v("colors.ring")} 30%, transparent)` : undefined,
        ...text("sm"),
      }}
      {...tokenAttrs("components.input.radius", "density.control", "colors.input", "colors.background", ...(focused ? ["colors.ring", "focusRing.width"] : []))}
    >
      <span className="min-w-0 flex-1 truncate">{value}</span>
      {children}
    </div>
  );
}

/** 侧栏行（components/src/lib.rs `sidebar_navigation_button` + downloads `nav_trailing`）。 */
function NavRow({
  icon,
  label,
  count,
  dot,
  selected,
  indent = false,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  count: number;
  dot?: string;
  selected: boolean;
  indent?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "group/nav flex w-full items-center text-left",
        !selected && "hover:bg-[var(--gt-colors-navHover)] hover:text-[var(--gt-colors-foreground)]",
      )}
      style={{
        height: v("density.navRow"),
        borderRadius: v("components.navItem.radius"),
        backgroundColor: selected ? v("colors.navSelected") : undefined,
        color: selected ? v("colors.navSelectedForeground") : v("colors.mutedForeground"),
        paddingLeft: indent ? `calc(${v("spacing.sm")} + ${v("spacing.lg")})` : v("spacing.sm"),
        paddingRight: v("spacing.sm"),
        gap: v("spacing.xs"),
        ...text("sm"),
        fontWeight: selected ? 500 : v("typography.sm.weight"),
      }}
      {...tokenAttrs(
        "density.navRow",
        "components.navItem.radius",
        "colors.navSelected",
        "colors.navHover",
        "colors.navSelectedForeground",
        "colors.mutedForeground",
        ...(indent ? ["spacing.lg"] : []),
      )}
    >
      <span className="flex min-w-0 flex-1 items-center" style={{ gap: v("spacing.sm") }}>
        {icon}
        <span className="min-w-0 flex-1 truncate">{label}</span>
      </span>
      {dot && <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: v(dot) }} {...tokenAttrs(dot)} />}
      {count > 0 && (
        <span
          className="shrink-0 tabular-nums"
          style={{ ...text("caption"), color: selected ? v("colors.mutedForeground") : v("colors.textTertiary") }}
          {...tokenAttrs("typography.caption.size", "colors.textTertiary")}
        >
          {count}
        </span>
      )}
    </button>
  );
}

/** 侧栏分区标题：悬停时才显出尾部按钮与展开箭头（sidebar.rs `section_header`）。 */
function SectionHeader({
  label,
  open,
  onToggle,
  trailing,
  first = false,
}: {
  label: string;
  open: boolean;
  onToggle: () => void;
  trailing?: { icon: Icon; label: string };
  first?: boolean;
}) {
  const Trailing = trailing?.icon;
  return (
    <div style={{ paddingTop: first ? undefined : v("spacing.md") }}>
      <button
        type="button"
        onClick={onToggle}
        className="group/section flex w-full items-center text-left hover:text-[var(--gt-colors-mutedForeground)]"
        style={{
          height: v("density.sectionHeader"),
          borderRadius: v("radius.md"),
          paddingInline: v("spacing.sm"),
          gap: v("spacing.xs"),
          color: v("colors.textTertiary"),
          ...text("caption"),
          fontWeight: 500,
        }}
        {...tokenAttrs("density.sectionHeader", "radius.md", "colors.textTertiary", "colors.mutedForeground", "typography.caption.size")}
      >
        <span className="min-w-0 flex-1 truncate">{label}</span>
        {Trailing && (
          <span
            title={trailing.label}
            className="invisible grid place-items-center group-hover/section:visible hover:bg-[var(--gt-colors-navHover)]"
            style={{ width: `calc(${v("icon.sm")} + 2 * ${v("spacing.xs")})`, height: `calc(${v("icon.sm")} + 2 * ${v("spacing.xs")})`, borderRadius: v("radius.sm"), color: v("colors.mutedForeground") }}
          >
            <Trailing style={iconSm} />
          </span>
        )}
        <ChevronRight className="invisible shrink-0 transition-transform group-hover/section:visible" style={{ ...iconSm, transform: open ? "rotate(90deg)" : undefined }} />
      </button>
    </div>
  );
}

function NewDownloadDialog({ t, onClose }: { t: PreviewMessages; onClose: () => void }) {
  const [tab, setTab] = useState<"basic" | "advanced">("basic");
  const [remember, setRemember] = useState(true);
  return (
    <div
      className="absolute inset-0 z-20 grid place-items-center p-6"
      style={{ backgroundColor: "rgba(0, 0, 0, 0.36)" }}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-label={t.dialogTitle}
        className="flex w-full max-w-[440px] flex-col"
        style={{
          backgroundColor: v("colors.surface"),
          color: v("colors.surfaceForeground"),
          borderRadius: v("components.dialog.radius"),
          border: `${v("stroke.thin")} solid ${v("colors.border")}`,
          boxShadow: v("shadow.lg"),
          padding: v("spacing.lg"),
          gap: v("spacing.md"),
        }}
        onClick={(e) => e.stopPropagation()}
        {...tokenAttrs("components.dialog.radius", "colors.surface", "colors.surfaceForeground", "colors.border", "shadow.lg", "spacing.lg")}
      >
        <div className="flex items-center justify-between">
          <span style={text("title")} {...tokenAttrs("typography.title.size", "typography.title.weight")}>
            {t.dialogTitle}
          </span>
          <button type="button" onClick={onClose} aria-label={t.cancel} style={{ color: v("colors.mutedForeground") }}>
            <X style={iconMd} />
          </button>
        </div>

        <div
          className="flex p-0.5"
          style={{ backgroundColor: v("colors.muted"), borderRadius: v("components.tab.radius"), gap: 2 }}
          {...tokenAttrs("components.tab.radius", "colors.muted")}
        >
          {(["basic", "advanced"] as const).map((key) => (
            <button
              key={key}
              type="button"
              onClick={() => setTab(key)}
              className="flex-1"
              style={{
                ...text("sm"),
                height: `calc(${v("density.control")} - 4px)`,
                borderRadius: v("components.tab.radius"),
                backgroundColor: tab === key ? v("colors.background") : "transparent",
                color: tab === key ? v("colors.foreground") : v("colors.mutedForeground"),
                boxShadow: tab === key ? v("shadow.sm") : undefined,
              }}
              {...tokenAttrs("components.tab.radius", "colors.background", "colors.mutedForeground", "shadow.sm")}
            >
              {key === "basic" ? t.tabBasic : t.tabAdvanced}
            </button>
          ))}
        </div>

        <label className="flex flex-col" style={{ gap: v("spacing.xs") }}>
          <span style={{ ...text("caption"), color: v("colors.mutedForeground") }}>{t.fieldUrl}</span>
          <Input value="https://releases.ubuntu.com/24.04/ubuntu-24.04.1-desktop-amd64.iso" focused />
        </label>
        <label className="flex flex-col" style={{ gap: v("spacing.xs") }}>
          <span style={{ ...text("caption"), color: v("colors.mutedForeground") }}>{t.fieldSaveDir}</span>
          <div className="flex" style={{ gap: v("spacing.xs") }}>
            <Input value="~/Downloads" />
            <Button variant="secondary">
              <Folder style={iconMd} />
              {t.browse}
            </Button>
          </div>
        </label>
        <div className="flex flex-wrap items-center" style={{ gap: v("spacing.xs") }}>
          <Badge tone="primary">HTTPS</Badge>
          <Badge tone="muted">{t.threads(16)}</Badge>
          <Badge tone="success">{t.resumable}</Badge>
          <Badge tone="warning">{t.largeFile}</Badge>
        </div>
        <button
          type="button"
          onClick={() => setRemember((value) => !value)}
          className="flex items-center justify-between text-left"
          style={{ ...text("sm"), paddingBlock: v("spacing.xs") }}
        >
          <span>{t.rememberDir}</span>
          <Switch on={remember} />
        </button>
        <div className="flex items-center justify-between" style={{ ...text("sm"), color: v("colors.mutedForeground") }}>
          <span>{t.startPaused}</span>
          <Switch on={false} />
        </div>

        <div className="flex justify-end" style={{ gap: v("spacing.sm"), paddingTop: v("spacing.xs") }}>
          <Button variant="secondary" onClick={onClose}>
            {t.cancel}
          </Button>
          <Button variant="primary" onClick={onClose}>
            <ArrowDownToLine style={iconMd} />
            {t.startDownload}
          </Button>
        </div>
      </div>
    </div>
  );
}

/** 访客是否在 macOS：决定顶栏走交通灯还是 logo + 应用菜单 + 窗口按钮（shell `render_title_bar`）。 */
function useIsMac(): boolean {
  const [isMac, setIsMac] = useState(false);
  useEffect(() => {
    const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
    setIsMac(/mac/i.test(nav.userAgentData?.platform ?? nav.userAgent));
  }, []);
  return isMac;
}

/** 状态栏按钮（status_bar.rs `status_button`）：ghost XSmall，高 = density.statusControl。 */
function StatusButton({ children, iconOnly = false }: { children: ReactNode; iconOnly?: boolean }) {
  return (
    <span
      className={cn("inline-flex shrink-0 items-center whitespace-nowrap hover:bg-[var(--gt-colors-navHover)]", iconOnly && "justify-center")}
      style={{
        height: v("density.statusControl"),
        minWidth: iconOnly ? v("density.statusControl") : undefined,
        paddingInline: v("spacing.xs"),
        gap: v("spacing.xxs"),
        borderRadius: v("components.button.radius"),
      }}
      {...tokenAttrs("density.statusControl", "spacing.xs", "components.button.radius", "colors.navHover")}
    >
      {children}
    </span>
  );
}

interface Column {
  key: "size" | "progress" | "status" | "created";
  label: string;
  width: number;
  numeric: boolean;
  className?: string;
}

export function GpuiPreview({
  tokens,
  mode,
  t,
  onToggleMode,
}: {
  tokens: ResolvedTokens;
  mode: ThemeMode;
  t: PreviewMessages;
  onToggleMode: () => void;
}) {
  const isMac = useIsMac();
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [sections, setSections] = useState<Record<Section, boolean>>({ status: true, queues: true, devices: true });
  const [folder, setFolder] = useState<StatusFolder>("all");
  const [category, setCategory] = useState<Category | null>(null);
  const [expanded, setExpanded] = useState<StatusFolder>("all");
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [dialog, setDialog] = useState(false);

  const visible = TASKS.filter((task) => FOLDER_MATCH[folder](task) && (category === null || task.category === category));
  const selectedTasks = TASKS.filter((task) => selected.has(task.id));
  const ModeIcon = mode === "dark" ? Sun : Moon;
  const activityIcon = { width: `calc(${v("icon.lg")} + 2px)`, height: `calc(${v("icon.lg")} + 2px)` };
  const contentLeft = ACTIVITY_RAIL_WIDTH + (sidebarOpen ? SIDEBAR_WIDTH : 0);

  const toggleSelected = (id: string) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const toggleSection = (section: Section) => setSections((current) => ({ ...current, [section]: !current[section] }));

  const columns: Column[] = [
    { key: "size", label: t.colSize, width: 84, numeric: true, className: "hidden @min-[578px]:flex" },
    { key: "progress", label: t.colProgress, width: 150, numeric: false },
    { key: "status", label: t.colStatus, width: 140, numeric: false },
    { key: "created", label: t.colCreated, width: 150, numeric: true, className: "hidden @min-[728px]:flex" },
  ];

  const statusLine = (task: DemoTask): { main: string; detail?: string } => {
    switch (task.status) {
      case "downloading":
        return {
          main: [task.speed, task.etaMinutes === undefined ? undefined : t.etaMinutes(task.etaMinutes)].filter(Boolean).join(" · ") || t.status.downloading,
          detail: `${task.downloaded} / ${task.size}${task.transfers ? ` · ${t.activeTransfers(task.transfers)}` : ""}`,
        };
      case "paused":
        return { main: t.status.paused, detail: `${task.downloaded} / ${task.size}` };
      case "failed":
        return { main: t.status.failed, detail: t.errorTimeout };
      case "queued":
        return { main: t.status.queued, detail: task.queuePosition ? t.queuePosition(task.queuePosition) : undefined };
      case "completed":
        return { main: t.status.completed };
    }
  };

  return (
    <div
      className="relative flex h-full min-h-0 flex-col overflow-hidden"
      style={{
        ...cssVariables(tokens),
        backgroundColor: v("colors.background"),
        color: v("colors.foreground"),
        fontFamily: v("typography.sans"),
        border: `${v("stroke.thin")} solid ${v("colors.border")}`,
        borderRadius: v("radius.lg"),
        boxShadow: v("shadow.lg"),
        ...text("sm"),
      }}
      {...tokenAttrs("colors.background", "colors.foreground", "typography.sans", "colors.border", "radius.lg", "shadow.lg")}
    >
      {/* 统一顶栏：crates/shell view.rs `render_title_bar` + downloads title_bar.rs 插槽 */}
      <div
        className="flex shrink-0 items-stretch"
        style={{ height: v("density.titleBar"), backgroundColor: v("colors.chrome"), borderBottom: hairline }}
        {...tokenAttrs("density.titleBar", "colors.chrome", "colors.hairline", "stroke.thin")}
      >
        <div className="flex min-w-0 flex-1 items-center" style={{ gap: v("spacing.sm"), paddingRight: isMac ? v("spacing.md") : v("spacing.sm") }}>
          {/* 前导区（交通灯 / logo + 菜单 + 侧栏开关）至少铺到内容区左缘，主按钮由 gap 留一档白 */}
          <div
            className="flex shrink-0 items-center self-stretch"
            style={{ minWidth: contentLeft, gap: v("spacing.sm"), paddingLeft: isMac ? undefined : v("spacing.sm") }}
          >
            {isMac ? (
              <span className="flex shrink-0 items-center gap-2 pl-3" style={{ width: MAC_TRAFFIC_LIGHT_WIDTH, marginRight: `calc(-1 * ${v("spacing.sm")})` }} aria-hidden>
                {["#ff5f57", "#febc2e", "#28c840"].map((color) => (
                  <span key={color} className="h-3 w-3 rounded-full" style={{ backgroundColor: color, boxShadow: "inset 0 0 0 0.5px rgba(0, 0, 0, 0.12)" }} />
                ))}
              </span>
            ) : (
              <>
                <img src={withBase("/logo.svg")} alt="" className="h-4 w-4 shrink-0" />
                <span className="flex shrink-0 items-center">
                  {[t.menus.file, t.menus.tasks, t.menus.view, t.menus.tools, t.menus.help].map((label) => (
                    <span
                      key={label}
                      className="inline-flex items-center whitespace-nowrap hover:bg-[var(--gt-colors-secondary)]"
                      style={{ height: `calc(${v("density.control")} - 4px)`, paddingInline: v("spacing.sm"), borderRadius: v("components.button.radius"), ...text("sm") }}
                      {...tokenAttrs("components.button.radius", "colors.secondary")}
                    >
                      {label}
                    </span>
                  ))}
                </span>
              </>
            )}
            <ToolbarIconButton icon={PanelLeft} label={sidebarOpen ? t.collapseSidebar : t.expandSidebar} onClick={() => setSidebarOpen((open) => !open)} />
          </div>
          <Button variant="primary" onClick={() => setDialog(true)}>
            <Plus style={iconLg} />
            {t.newDownload}
          </Button>
          <div className="min-w-0 flex-1" />
          <div
            className="flex min-w-[160px] shrink items-center hover:bg-[var(--gt-colors-navSelected)]"
            style={{
              width: 280,
              height: v("density.control"),
              paddingLeft: v("spacing.sm"),
              paddingRight: v("spacing.xs"),
              gap: v("spacing.xs"),
              borderRadius: v("radius.md"),
              border: `${v("stroke.thin")} solid transparent`,
              backgroundColor: v("colors.navHover"),
              ...text("sm"),
            }}
            {...tokenAttrs("density.control", "radius.md", "colors.navHover", "colors.navSelected", "colors.mutedForeground")}
          >
            <Search className="shrink-0" style={{ ...iconMd, color: v("colors.mutedForeground") }} />
            <span className="min-w-0 flex-1 truncate" style={{ color: v("colors.mutedForeground") }}>
              {t.searchPlaceholder}
            </span>
            <span
              className="shrink-0"
              style={{
                ...text("caption"),
                color: v("colors.textTertiary"),
                backgroundColor: v("colors.surface"),
                borderRadius: v("radius.sm"),
                border: hairline,
                paddingInline: v("spacing.xs"),
              }}
              {...tokenAttrs("radius.sm", "colors.surface", "colors.hairline", "colors.textTertiary", "typography.caption.size")}
            >
              {isMac ? "⌘F" : "Ctrl+F"}
            </span>
          </div>
          <Button variant="ghost">
            <SlidersHorizontal style={iconLg} />
            {t.view}
          </Button>
        </div>
        {!isMac && (
          <div className="flex shrink-0 items-stretch" style={{ color: v("colors.mutedForeground") }} {...tokenAttrs("colors.mutedForeground")}>
            {[Minus, Square, X].map((IconComponent, index) => (
              <span key={index} className="grid place-items-center" style={{ width: WINDOW_CONTROL_WIDTH }} aria-hidden>
                <IconComponent style={index === 1 ? iconSm : iconMd} />
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="flex min-h-0 flex-1">
        {/* 活动栏：48px，chrome 底，32px 按钮 */}
        <div
          className="flex shrink-0 flex-col items-center justify-between"
          style={{ width: ACTIVITY_RAIL_WIDTH, backgroundColor: v("colors.chrome"), borderRight: hairline, paddingBlock: v("spacing.sm") }}
          {...tokenAttrs("colors.chrome", "colors.hairline")}
        >
          <div className="flex flex-col" style={{ gap: v("spacing.xs") }}>
            {[Download, Rss, Webhook].map((IconComponent, index) => (
              <span
                key={index}
                className={cn("grid h-8 w-8 place-items-center", index !== 0 && "hover:bg-[var(--gt-colors-navHover)] hover:text-[var(--gt-colors-foreground)]")}
                style={{
                  borderRadius: v("components.navItem.radius"),
                  backgroundColor: index === 0 ? v("colors.navSelected") : undefined,
                  color: index === 0 ? v("colors.navSelectedIcon") : v("colors.mutedForeground"),
                }}
                {...tokenAttrs("components.navItem.radius", "colors.navSelected", "colors.navHover", "colors.navSelectedIcon", "colors.mutedForeground", "icon.lg")}
              >
                <IconComponent style={activityIcon} />
              </span>
            ))}
          </div>
          <div className="flex flex-col" style={{ gap: v("spacing.xs") }}>
            <button
              type="button"
              onClick={onToggleMode}
              title={t.toggleMode}
              aria-label={t.toggleMode}
              className="grid h-8 w-8 place-items-center hover:bg-[var(--gt-colors-navHover)]"
              style={{ borderRadius: v("components.navItem.radius"), color: v("colors.mutedForeground") }}
              {...tokenAttrs("components.navItem.radius", "colors.navHover", "colors.mutedForeground")}
            >
              <ModeIcon style={activityIcon} />
            </button>
            <span
              className="grid h-8 w-8 place-items-center hover:bg-[var(--gt-colors-navHover)]"
              style={{ borderRadius: v("components.navItem.radius"), color: v("colors.mutedForeground") }}
            >
              <Settings style={activityIcon} />
            </span>
          </div>
        </div>

        {/* 下载页：[侧栏 | 内容] + 页内状态栏（状态栏不跨活动栏） */}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex min-h-0 flex-1">
            {sidebarOpen && (
              <div
                className="flex shrink-0 flex-col overflow-y-auto [scrollbar-width:none]"
                style={{ width: SIDEBAR_WIDTH, backgroundColor: v("colors.chrome"), borderRight: hairline, paddingInline: v("spacing.sm"), paddingTop: v("spacing.sm") }}
                {...tokenAttrs("colors.chrome", "colors.hairline", "spacing.sm")}
              >
                <SectionHeader label={t.sectionStatus} open={sections.status} onToggle={() => toggleSection("status")} first />
                {sections.status &&
                  FOLDERS.map((key) => {
                    const FolderIcon = FOLDER_ICON[key];
                    const count = TASKS.filter(FOLDER_MATCH[key]).length;
                    const active = folder === key && category === null;
                    const open = expanded === key;
                    const iconColor =
                      key === "failed" && count > 0
                        ? v("colors.destructive")
                        : (key === "downloading" && count > 0) || active
                          ? v("colors.navSelectedIcon")
                          : v("colors.mutedForeground");
                    return (
                      <div key={key}>
                        <NavRow
                          selected={active}
                          count={count}
                          label={t.folders[key]}
                          onClick={() => {
                            setFolder(key);
                            setCategory(null);
                            setExpanded(key);
                          }}
                          icon={
                            <span className="grid shrink-0 place-items-center" style={iconLg} {...tokenAttrs("colors.navSelectedIcon", "colors.destructive")}>
                              <FolderIcon className="group-hover/nav:hidden" style={{ ...iconLg, color: iconColor }} />
                              <ChevronRight
                                className="hidden transition-transform group-hover/nav:block"
                                style={{ ...iconMd, transform: open ? "rotate(90deg)" : undefined }}
                              />
                            </span>
                          }
                        />
                        {open &&
                          CATEGORIES.map((cat) => {
                            const CatIcon = CATEGORY_ICON[cat];
                            const catActive = folder === key && category === cat;
                            return (
                              <NavRow
                                key={cat}
                                indent
                                selected={catActive}
                                count={TASKS.filter((task) => FOLDER_MATCH[key](task) && task.category === cat).length}
                                label={t.categories[cat]}
                                onClick={() => {
                                  setFolder(key);
                                  setCategory(cat);
                                }}
                                icon={<CatIcon className="shrink-0" style={{ ...iconMd, color: catActive ? v("colors.navSelectedIcon") : v("colors.mutedForeground") }} />}
                              />
                            );
                          })}
                      </div>
                    );
                  })}

                <SectionHeader
                  label={t.sectionQueues}
                  open={sections.queues}
                  onToggle={() => toggleSection("queues")}
                  trailing={{ icon: Settings, label: t.manageQueues }}
                />
                {sections.queues &&
                  [
                    { name: t.queueMain, count: 6, running: true },
                    { name: t.queueLater, count: 1, running: false },
                  ].map((queue) => (
                    <NavRow
                      key={queue.name}
                      selected={false}
                      count={queue.count}
                      dot={queue.running ? "colors.success" : undefined}
                      label={queue.name}
                      icon={<Rows3 className="shrink-0" style={{ ...iconLg, color: v("colors.mutedForeground") }} />}
                    />
                  ))}

                <SectionHeader
                  label={t.sectionDevices}
                  open={sections.devices}
                  onToggle={() => toggleSection("devices")}
                  trailing={{ icon: Plus, label: t.addDevice }}
                />
                {sections.devices &&
                  [
                    { name: t.allDevices, Icon: Layers, count: 9, dot: undefined },
                    { name: t.thisDevice, Icon: Cpu, count: TASKS.length, dot: undefined },
                    { name: "FluxDown NAS", Icon: Globe, count: 2, dot: "colors.success" },
                  ].map((device) => (
                    <NavRow
                      key={device.name}
                      selected={false}
                      count={device.count}
                      dot={device.dot}
                      label={device.name}
                      icon={<device.Icon className="shrink-0" style={{ ...iconLg, color: v("colors.mutedForeground") }} />}
                    />
                  ))}
                <div className="shrink-0" style={{ height: v("spacing.sm") }} />
              </div>
            )}

            {/* 内容区：surface 底、无网格线任务表；选中时选择条盖住表头 */}
            <div className="@container relative flex min-w-0 flex-1 flex-col" style={{ backgroundColor: v("colors.surface") }} {...tokenAttrs("colors.surface")}>
              <div
                className="flex shrink-0 items-center"
                style={{
                  height: TABLE_HEADER_HEIGHT,
                  borderBottom: hairline,
                  color: v("colors.textTertiary"),
                  ...text("xs"),
                  fontWeight: 500,
                }}
                {...tokenAttrs("colors.textTertiary", "typography.xs.size", "colors.hairline")}
              >
                <span className="shrink-0" style={{ width: SELECTION_COLUMN_WIDTH }} />
                <span className="flex h-full min-w-[160px] flex-1 items-center px-2">
                  <span className="min-w-0 flex-1 truncate">{t.colName}</span>
                  <span className="shrink-0" style={{ width: v("stroke.thin"), height: 14, backgroundColor: v("colors.hairline") }} />
                </span>
                {columns.map((column) => (
                  <span key={column.key} className={cn("flex h-full shrink-0 items-center", column.className)} style={{ width: column.width }}>
                    <span className={cn("min-w-0 flex-1 truncate px-2", column.numeric && "text-right")}>{column.label}</span>
                    <span className="shrink-0" style={{ width: v("stroke.thin"), height: 14, backgroundColor: v("colors.hairline") }} />
                  </span>
                ))}
                <span className="shrink-0" style={{ width: TABLE_TRAILING_GUTTER }} />
              </div>

              <div className="min-h-0 flex-1 overflow-y-auto [scrollbar-width:thin]">
                {visible.length === 0 && (
                  <p className="py-10 text-center" style={{ color: v("colors.textTertiary") }}>
                    {t.noTasks}
                  </p>
                )}
                {visible.map((task) => {
                  const isSelected = selected.has(task.id);
                  const KindIcon = KIND_ICON[task.kind];
                  const status = statusLine(task);
                  return (
                    <div
                      key={task.id}
                      onClick={() => toggleSelected(task.id)}
                      className="group/row relative flex cursor-default items-center"
                      style={{ height: v("density.taskRow") }}
                      {...tokenAttrs("density.taskRow", "components.taskRow.radius", "colors.rowHover", "colors.accent")}
                    >
                      <span
                        className={cn("absolute inset-y-0 left-1 right-1", !isSelected && "group-hover/row:bg-[var(--gt-colors-rowHover)]")}
                        style={{ borderRadius: v("components.taskRow.radius"), backgroundColor: isSelected ? v("colors.accent") : undefined }}
                      />
                      <span className="relative grid h-full shrink-0 place-items-center" style={{ width: SELECTION_COLUMN_WIDTH, color: v("colors.mutedForeground") }}>
                        {isSelected || selected.size > 0 ? (
                          <CheckMark checked={isSelected} />
                        ) : (
                          <>
                            <KindIcon className="group-hover/row:hidden" style={iconLg} />
                            <span className="hidden group-hover/row:block">
                              <CheckMark checked={false} />
                            </span>
                          </>
                        )}
                      </span>
                      <span className="relative flex min-w-[160px] flex-1 flex-col justify-center px-2">
                        <span className="truncate" style={{ ...text("sm"), color: v("colors.foreground") }} {...tokenAttrs("typography.sm.size", "colors.foreground")}>
                          {task.name}
                        </span>
                        <span className="truncate" style={{ ...text("xs"), color: v("colors.textTertiary") }} {...tokenAttrs("typography.xs.size", "colors.textTertiary")}>
                          {`${t.categories[task.category]} · ${task.site}`}
                        </span>
                      </span>
                      {columns.map((column) => {
                        const cellClass = cn("relative h-full shrink-0 px-2", column.className ?? "flex");
                        if (column.key === "size" || column.key === "created") {
                          return (
                            <span
                              key={column.key}
                              className={cn(cellClass, "items-center justify-end tabular-nums")}
                              style={{ width: column.width, color: v("colors.mutedForeground"), ...text("xs") }}
                              {...tokenAttrs("typography.xs.size", "colors.mutedForeground")}
                            >
                              <span className="truncate">{column.key === "size" ? task.size : task.created}</span>
                            </span>
                          );
                        }
                        if (column.key === "progress") {
                          return (
                            <span key={column.key} className={cn(cellClass, "items-center")} style={{ width: column.width, gap: 8 }}>
                              {task.status !== "completed" && (
                                <>
                                  <ProgressBar task={task} />
                                  <span
                                    className="shrink-0 text-right tabular-nums"
                                    style={{ width: PROGRESS_LABEL_WIDTH, color: v("colors.mutedForeground"), ...text("xs") }}
                                    {...tokenAttrs("typography.xs.size", "colors.mutedForeground")}
                                  >
                                    {percentLabel(task.progress)}
                                  </span>
                                </>
                              )}
                            </span>
                          );
                        }
                        return (
                          <span key={column.key} className={cn(cellClass, "flex-col justify-center tabular-nums")} style={{ width: column.width, ...text("xs") }}>
                            <span className="truncate" style={{ color: v(STATUS_TEXT[task.status]) }} {...tokenAttrs(STATUS_TEXT[task.status])}>
                              {status.main}
                            </span>
                            {status.detail && (
                              <span className="truncate" style={{ color: v("colors.textTertiary") }} {...tokenAttrs("colors.textTertiary")}>
                                {status.detail}
                              </span>
                            )}
                          </span>
                        );
                      })}
                      <span className="shrink-0" style={{ width: TABLE_TRAILING_GUTTER }} />
                    </div>
                  );
                })}
              </div>

              {selected.size > 0 && (
                <div
                  className="absolute right-0 top-0 z-10 flex items-center"
                  style={{
                    left: SELECTION_COLUMN_WIDTH,
                    height: TABLE_HEADER_HEIGHT - 1,
                    backgroundColor: v("colors.surface"),
                    paddingLeft: v("spacing.sm"),
                    gap: v("spacing.xxs"),
                  }}
                  {...tokenAttrs("colors.surface", "spacing.sm", "spacing.xxs")}
                >
                  <span className="whitespace-nowrap tabular-nums" style={{ ...text("xs"), color: v("colors.foreground") }}>
                    {t.selected(selected.size)}
                  </span>
                  <span className="mx-1 shrink-0" style={{ width: v("stroke.thin"), height: 16, backgroundColor: v("colors.hairline") }} />
                  {selectedTasks.some((task) => task.status === "paused" || task.status === "failed") && <ToolbarIconButton icon={Play} label={t.resume} />}
                  {selectedTasks.some((task) => task.status === "downloading" || task.status === "queued") && <ToolbarIconButton icon={Pause} label={t.pause} />}
                  <ToolbarIconButton icon={ExternalLink} label={t.openFile} />
                  <ToolbarIconButton icon={FolderOpen} label={t.openFolder} />
                  <ToolbarIconButton icon={Trash2} label={t.deleteTask} destructive />
                  <span className="mx-1 shrink-0" style={{ width: v("stroke.thin"), height: 16, backgroundColor: v("colors.hairline") }} />
                  <ToolbarIconButton icon={X} label={t.clearSelection} onClick={() => setSelected(new Set())} />
                </div>
              )}
            </div>
          </div>

          {/* 状态栏：status_bar.rs `render_status_bar` */}
          <div
            className="flex shrink-0 items-center justify-between overflow-hidden tabular-nums"
            style={{
              height: v("density.statusBar"),
              backgroundColor: v("colors.chrome"),
              borderTop: hairline,
              paddingInline: v("spacing.sm"),
              gap: v("spacing.md"),
              color: v("colors.mutedForeground"),
              ...text("caption"),
            }}
            {...tokenAttrs("density.statusBar", "colors.chrome", "colors.hairline", "colors.mutedForeground", "typography.caption.size")}
          >
            <span className="flex shrink-0 items-center" style={{ gap: v("spacing.md") }}>
              <span className="inline-flex items-center whitespace-nowrap" style={{ gap: v("spacing.xxs") }}>
                <ArrowDown style={iconSm} />
                12.8 MB/s
              </span>
              <span className="inline-flex items-center whitespace-nowrap" style={{ gap: v("spacing.xxs") }}>
                <ArrowUp style={iconSm} />
                0 B/s
              </span>
              <span className="inline-flex items-center" style={{ gap: v("spacing.xxs") }}>
                <ToolbarIconButton icon={Pause} label={t.pauseAll} size={v("density.statusControl")} iconStyle={iconSm} />
                <ToolbarIconButton icon={Play} label={t.resumeAll} size={v("density.statusControl")} iconStyle={iconSm} />
              </span>
            </span>
            <span className="flex min-w-0 items-center" style={{ gap: v("spacing.xs") }}>
              <StatusButton>
                <ArrowDown style={iconSm} />
                {t.unlimited}
              </StatusButton>
              <StatusButton>
                <ArrowUp style={iconSm} />
                {t.unlimited}
              </StatusButton>
              <StatusButton>
                <Globe style={iconSm} />
                {t.proxyAuto}
                <ChevronDown style={iconSm} />
              </StatusButton>
              <StatusButton iconOnly>
                <Power style={iconSm} />
              </StatusButton>
              <span className="inline-flex shrink-0 items-center whitespace-nowrap" style={{ paddingInline: v("spacing.xs"), gap: v("spacing.xxs") }}>
                <HardDrive style={iconSm} />
                {t.freeSpace("128 GB")}
              </span>
            </span>
          </div>
        </div>
      </div>

      {dialog && <NewDownloadDialog t={t} onClose={() => setDialog(false)} />}
    </div>
  );
}
