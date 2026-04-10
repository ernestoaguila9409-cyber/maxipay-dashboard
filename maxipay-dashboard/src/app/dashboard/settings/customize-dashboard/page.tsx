"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import { doc, setDoc, onSnapshot } from "firebase/firestore";
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  rectSortingStrategy,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  GripVertical,
  Save,
  RotateCcw,
  UtensilsCrossed,
  ShoppingBag,
  Wine,
  Receipt,
  CreditCard,
  Users,
  UserCircle,
  ClipboardList,
  Settings,
  SlidersHorizontal,
  Package,
  BarChart3,
  DollarSign,
  Menu,
  Home,
  Star,
  ShoppingCart,
  Store,
  ClipboardCheck,
  TrendingUp,
  Landmark,
  LayoutGrid,
  Truck,
  Wallet,
  ChefHat,
  FileText,
  Banknote,
  Warehouse,
  X,
  Check,
  Palette,
  Search,
  Ban,
  Coffee,
  Beer,
  Pizza,
  Sandwich,
  Apple,
  Cake,
  Coins,
  PiggyBank,
  Percent,
  Tag,
  UserPlus,
  FolderOpen,
  Calendar,
  Clock,
  Timer,
  PieChart,
  LineChart,
  Activity,
  Wrench,
  Shield,
  Key,
  Lock,
  Bell,
  Eye,
  MapPin,
  Printer,
  Wifi,
  QrCode,
  Building2,
  Phone,
  Mail,
  MessageSquare,
  Gift,
  Heart,
  Zap,
  Flame,
  Award,
  Crown,
  Target,
  Sparkles,
  Power,
  Download,
  Upload,
  RefreshCw,
  Trash2,
  Plus,
  Globe,
  Bookmark,
  ListOrdered,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

// ── Types ──────────────────────────────────────────

interface DashboardModule {
  key: string;
  label: string;
  iconName: string;
  colorKey: string;
  position: number;
}

interface SearchableIcon {
  name: string;
  label: string;
  Icon: LucideIcon;
  tags: string[];
}

// ── Icon registry: maps icon names → lucide components ──

const ICON_MAP: Record<string, LucideIcon> = {
  none: Ban,
  ic_dine_in: UtensilsCrossed,
  ic_to_go: ShoppingBag,
  ic_bar: Wine,
  ic_transactions: Receipt,
  ic_settle_batch: CreditCard,
  ic_employees: Users,
  ic_customers: UserCircle,
  ic_orders: ClipboardList,
  ic_settings: Settings,
  ic_modifiers: SlidersHorizontal,
  ic_inventory: Package,
  ic_reports: BarChart3,
  ic_cash: DollarSign,
  ic_menu: Menu,
  ic_credit_card: CreditCard,
  ic_home: Home,
  ic_star: Star,
  ic_shopping_cart: ShoppingCart,
  ic_store: Store,
  ic_assessment: ClipboardCheck,
  ic_trending_up: TrendingUp,
  ic_account_balance: Landmark,
  ic_dashboard_grid: LayoutGrid,
  ic_local_shipping: Truck,
  ic_payment: Wallet,
  ic_restaurant: ChefHat,
  ic_receipt_long: FileText,
  ic_attach_money: Banknote,
  ic_warehouse: Warehouse,
  ic_local_dining: UtensilsCrossed,
  ic_coffee: Coffee,
  ic_beer: Beer,
  ic_pizza: Pizza,
  ic_sandwich: Sandwich,
  ic_apple: Apple,
  ic_cake: Cake,
  ic_tag: Tag,
  ic_coins: Coins,
  ic_piggy_bank: PiggyBank,
  ic_percent: Percent,
  ic_user_plus: UserPlus,
  ic_list: ListOrdered,
  ic_folder: FolderOpen,
  ic_calendar: Calendar,
  ic_clock: Clock,
  ic_timer: Timer,
  ic_pie_chart: PieChart,
  ic_line_chart: LineChart,
  ic_activity: Activity,
  ic_wrench: Wrench,
  ic_shield: Shield,
  ic_key: Key,
  ic_lock: Lock,
  ic_bell: Bell,
  ic_eye: Eye,
  ic_map_pin: MapPin,
  ic_printer: Printer,
  ic_wifi: Wifi,
  ic_qr_code: QrCode,
  ic_building: Building2,
  ic_phone: Phone,
  ic_mail: Mail,
  ic_message: MessageSquare,
  ic_gift: Gift,
  ic_heart: Heart,
  ic_zap: Zap,
  ic_flame: Flame,
  ic_award: Award,
  ic_crown: Crown,
  ic_target: Target,
  ic_sparkles: Sparkles,
  ic_power: Power,
  ic_download: Download,
  ic_upload: Upload,
  ic_refresh: RefreshCw,
  ic_trash: Trash2,
  ic_plus: Plus,
  ic_search: Search,
  ic_globe: Globe,
  ic_bookmark: Bookmark,
};

// ── Searchable icon catalog with keyword tags ──

const SEARCHABLE_ICONS: SearchableIcon[] = [
  { name: "none", label: "No Icon", Icon: Ban, tags: ["none", "empty", "remove", "clear", "default"] },

  // Food & Dining
  { name: "ic_dine_in", label: "Dine In", Icon: UtensilsCrossed, tags: ["food", "dining", "restaurant", "eat", "utensils", "fork", "knife", "dine"] },
  { name: "ic_restaurant", label: "Restaurant", Icon: ChefHat, tags: ["chef", "cook", "kitchen", "food", "restaurant", "hat"] },
  { name: "ic_bar", label: "Bar", Icon: Wine, tags: ["wine", "bar", "drink", "beverage", "alcohol", "glass"] },
  { name: "ic_coffee", label: "Coffee", Icon: Coffee, tags: ["coffee", "drink", "beverage", "cafe", "cup", "hot"] },
  { name: "ic_beer", label: "Beer", Icon: Beer, tags: ["beer", "drink", "bar", "alcohol", "pub", "glass"] },
  { name: "ic_pizza", label: "Pizza", Icon: Pizza, tags: ["pizza", "food", "fast", "slice", "italian"] },
  { name: "ic_sandwich", label: "Sandwich", Icon: Sandwich, tags: ["sandwich", "food", "lunch", "meal", "sub"] },
  { name: "ic_apple", label: "Apple", Icon: Apple, tags: ["apple", "food", "fruit", "healthy", "fresh"] },
  { name: "ic_cake", label: "Cake", Icon: Cake, tags: ["cake", "dessert", "sweet", "birthday", "bakery", "pastry"] },

  // Shopping & Commerce
  { name: "ic_to_go", label: "To Go", Icon: ShoppingBag, tags: ["bag", "shopping", "purchase", "takeout", "carry", "to go"] },
  { name: "ic_shopping_cart", label: "Cart", Icon: ShoppingCart, tags: ["cart", "shopping", "buy", "purchase", "checkout"] },
  { name: "ic_store", label: "Store", Icon: Store, tags: ["store", "shop", "retail", "building", "front"] },
  { name: "ic_tag", label: "Tag", Icon: Tag, tags: ["tag", "price", "label", "sale", "discount"] },

  // Finance & Payment
  { name: "ic_cash", label: "Cash", Icon: DollarSign, tags: ["money", "dollar", "currency", "finance", "price", "cash"] },
  { name: "ic_attach_money", label: "Money", Icon: Banknote, tags: ["money", "cash", "bill", "payment", "banknote"] },
  { name: "ic_credit_card", label: "Credit Card", Icon: CreditCard, tags: ["card", "payment", "credit", "debit", "swipe"] },
  { name: "ic_payment", label: "Payment", Icon: Wallet, tags: ["wallet", "payment", "money", "pay", "purse"] },
  { name: "ic_settle_batch", label: "Settle Batch", Icon: CreditCard, tags: ["settle", "batch", "card", "close", "end of day"] },
  { name: "ic_account_balance", label: "Finance", Icon: Landmark, tags: ["bank", "finance", "building", "institution", "account", "balance"] },
  { name: "ic_coins", label: "Coins", Icon: Coins, tags: ["coins", "money", "change", "currency", "cent"] },
  { name: "ic_piggy_bank", label: "Savings", Icon: PiggyBank, tags: ["savings", "money", "bank", "piggy", "save"] },
  { name: "ic_percent", label: "Percent", Icon: Percent, tags: ["percent", "discount", "sale", "off", "tax", "tip"] },

  // People
  { name: "ic_employees", label: "Employees", Icon: Users, tags: ["employees", "people", "team", "group", "staff", "crew"] },
  { name: "ic_customers", label: "Customers", Icon: UserCircle, tags: ["customer", "user", "person", "profile", "account"] },
  { name: "ic_user_plus", label: "Add User", Icon: UserPlus, tags: ["add", "new", "user", "customer", "register", "signup"] },

  // Organization & Orders
  { name: "ic_orders", label: "Orders", Icon: ClipboardList, tags: ["orders", "list", "clipboard", "tasks", "queue"] },
  { name: "ic_transactions", label: "Transactions", Icon: Receipt, tags: ["receipt", "transaction", "invoice", "bill", "record"] },
  { name: "ic_receipt_long", label: "Receipt", Icon: FileText, tags: ["receipt", "file", "document", "text", "report"] },
  { name: "ic_assessment", label: "Assessment", Icon: ClipboardCheck, tags: ["check", "done", "complete", "assessment", "verify", "audit"] },
  { name: "ic_list", label: "List", Icon: ListOrdered, tags: ["list", "ordered", "number", "sequence", "items"] },
  { name: "ic_folder", label: "Folder", Icon: FolderOpen, tags: ["folder", "files", "organize", "open", "directory"] },

  // Time & Schedule
  { name: "ic_calendar", label: "Calendar", Icon: Calendar, tags: ["calendar", "date", "schedule", "event", "day", "month"] },
  { name: "ic_clock", label: "Clock", Icon: Clock, tags: ["clock", "time", "hours", "schedule", "watch"] },
  { name: "ic_timer", label: "Timer", Icon: Timer, tags: ["timer", "countdown", "speed", "fast", "quick"] },

  // Analytics & Reports
  { name: "ic_reports", label: "Reports", Icon: BarChart3, tags: ["chart", "report", "analytics", "statistics", "bar", "graph"] },
  { name: "ic_trending_up", label: "Trending", Icon: TrendingUp, tags: ["trending", "growth", "increase", "analytics", "up"] },
  { name: "ic_pie_chart", label: "Pie Chart", Icon: PieChart, tags: ["pie", "chart", "analytics", "breakdown", "circle", "graph"] },
  { name: "ic_line_chart", label: "Line Chart", Icon: LineChart, tags: ["line", "chart", "trend", "analytics", "graph"] },
  { name: "ic_activity", label: "Activity", Icon: Activity, tags: ["activity", "pulse", "performance", "health", "monitor"] },

  // Settings & Tools
  { name: "ic_settings", label: "Settings", Icon: Settings, tags: ["settings", "config", "gear", "preferences", "setup"] },
  { name: "ic_modifiers", label: "Modifiers", Icon: SlidersHorizontal, tags: ["sliders", "adjust", "modifiers", "customize", "options"] },
  { name: "ic_wrench", label: "Wrench", Icon: Wrench, tags: ["tool", "wrench", "fix", "maintenance", "repair"] },
  { name: "ic_shield", label: "Shield", Icon: Shield, tags: ["security", "protection", "shield", "safe", "guard"] },
  { name: "ic_key", label: "Key", Icon: Key, tags: ["key", "access", "security", "lock", "password"] },
  { name: "ic_lock", label: "Lock", Icon: Lock, tags: ["lock", "security", "private", "password", "secure"] },

  // Layout & Navigation
  { name: "ic_dashboard_grid", label: "Dashboard", Icon: LayoutGrid, tags: ["grid", "dashboard", "layout", "tiles", "overview"] },
  { name: "ic_menu", label: "Menu", Icon: Menu, tags: ["menu", "hamburger", "list", "navigation", "sidebar"] },
  { name: "ic_home", label: "Home", Icon: Home, tags: ["home", "main", "start", "house", "landing"] },
  { name: "ic_star", label: "Star", Icon: Star, tags: ["star", "favorite", "bookmark", "rating", "best"] },
  { name: "ic_bookmark", label: "Bookmark", Icon: Bookmark, tags: ["bookmark", "save", "favorite", "mark", "flag"] },

  // Communication
  { name: "ic_bell", label: "Bell", Icon: Bell, tags: ["notification", "alert", "bell", "alarm", "ring"] },
  { name: "ic_phone", label: "Phone", Icon: Phone, tags: ["phone", "call", "contact", "telephone", "dial"] },
  { name: "ic_mail", label: "Mail", Icon: Mail, tags: ["email", "mail", "message", "send", "inbox", "letter"] },
  { name: "ic_message", label: "Message", Icon: MessageSquare, tags: ["message", "chat", "comment", "text", "sms"] },

  // Location & Logistics
  { name: "ic_local_shipping", label: "Shipping", Icon: Truck, tags: ["truck", "delivery", "shipping", "transport", "logistics"] },
  { name: "ic_warehouse", label: "Warehouse", Icon: Warehouse, tags: ["warehouse", "storage", "inventory", "depot", "stock"] },
  { name: "ic_inventory", label: "Inventory", Icon: Package, tags: ["package", "box", "delivery", "shipping", "inventory", "product"] },
  { name: "ic_map_pin", label: "Location", Icon: MapPin, tags: ["location", "map", "pin", "address", "place", "gps"] },
  { name: "ic_globe", label: "Globe", Icon: Globe, tags: ["globe", "world", "internet", "web", "global", "earth"] },
  { name: "ic_building", label: "Building", Icon: Building2, tags: ["building", "office", "company", "business", "corporate"] },

  // Misc
  { name: "ic_printer", label: "Printer", Icon: Printer, tags: ["print", "printer", "receipt", "paper", "output"] },
  { name: "ic_qr_code", label: "QR Code", Icon: QrCode, tags: ["qr", "scan", "code", "barcode", "mobile"] },
  { name: "ic_gift", label: "Gift", Icon: Gift, tags: ["gift", "reward", "bonus", "present", "loyalty"] },
  { name: "ic_heart", label: "Heart", Icon: Heart, tags: ["heart", "favorite", "like", "love", "loyalty"] },
  { name: "ic_zap", label: "Zap", Icon: Zap, tags: ["fast", "electric", "quick", "lightning", "power", "energy"] },
  { name: "ic_flame", label: "Flame", Icon: Flame, tags: ["hot", "fire", "popular", "trending", "spicy"] },
  { name: "ic_award", label: "Award", Icon: Award, tags: ["award", "achievement", "badge", "trophy", "medal"] },
  { name: "ic_crown", label: "Crown", Icon: Crown, tags: ["premium", "vip", "crown", "best", "king", "royal"] },
  { name: "ic_target", label: "Target", Icon: Target, tags: ["target", "goal", "aim", "focus", "objective"] },
  { name: "ic_sparkles", label: "Sparkles", Icon: Sparkles, tags: ["sparkle", "new", "magic", "special", "highlight"] },
  { name: "ic_power", label: "Power", Icon: Power, tags: ["power", "on", "off", "switch", "toggle", "energy"] },
  { name: "ic_eye", label: "Eye", Icon: Eye, tags: ["view", "visibility", "watch", "see", "look", "preview"] },
  { name: "ic_wifi", label: "WiFi", Icon: Wifi, tags: ["wifi", "internet", "connection", "network", "wireless"] },
  { name: "ic_download", label: "Download", Icon: Download, tags: ["download", "export", "save", "file"] },
  { name: "ic_upload", label: "Upload", Icon: Upload, tags: ["upload", "import", "file", "share"] },
  { name: "ic_refresh", label: "Refresh", Icon: RefreshCw, tags: ["refresh", "sync", "reload", "update", "rotate"] },
  { name: "ic_trash", label: "Trash", Icon: Trash2, tags: ["delete", "remove", "trash", "bin", "discard"] },
  { name: "ic_plus", label: "Plus", Icon: Plus, tags: ["add", "new", "create", "plus", "more"] },
  { name: "ic_search", label: "Search", Icon: Search, tags: ["search", "find", "lookup", "magnify"] },
];

const DEFAULT_MODULES: DashboardModule[] = [
  { key: "dine_in", label: "DINE IN", iconName: "ic_dine_in", colorKey: "green", position: 0 },
  { key: "to_go", label: "TO-GO", iconName: "ic_to_go", colorKey: "orange", position: 1 },
  { key: "bar", label: "BAR", iconName: "ic_bar", colorKey: "teal", position: 2 },
  { key: "transactions", label: "TRANSACTIONS", iconName: "ic_transactions", colorKey: "purple", position: 3 },
  { key: "settle_batch", label: "SETTLE BATCH", iconName: "ic_settle_batch", colorKey: "purple", position: 4 },
  { key: "employees", label: "EMPLOYEES", iconName: "ic_employees", colorKey: "purple", position: 5 },
  { key: "customers", label: "CUSTOMERS", iconName: "ic_customers", colorKey: "purple", position: 6 },
  { key: "orders", label: "ORDERS", iconName: "ic_orders", colorKey: "purple", position: 7 },
  { key: "setup", label: "SETUP", iconName: "ic_settings", colorKey: "purple", position: 8 },
  { key: "modifiers", label: "MODIFIERS", iconName: "ic_modifiers", colorKey: "purple", position: 9 },
  { key: "inventory", label: "INVENTORY", iconName: "ic_inventory", colorKey: "purple", position: 10 },
  { key: "reports", label: "REPORTS", iconName: "ic_reports", colorKey: "purple", position: 11 },
  { key: "printers", label: "PRINTERS", iconName: "ic_printer", colorKey: "purple", position: 12 },
  { key: "cash_flow", label: "CASH FLOW", iconName: "ic_cash", colorKey: "purple", position: 13 },
  { key: "reservation", label: "RESERVATION", iconName: "ic_calendar", colorKey: "purple", position: 14 },
];

// Must match Android ColorRegistry
const COLOR_PALETTE: { key: string; label: string; tailwind: string; hex: string }[] = [
  { key: "green", label: "Green", tailwind: "bg-green-700", hex: "#2E7D32" },
  { key: "orange", label: "Orange", tailwind: "bg-orange-700", hex: "#E65100" },
  { key: "teal", label: "Teal", tailwind: "bg-teal-600", hex: "#00897B" },
  { key: "purple", label: "Purple", tailwind: "bg-purple-600", hex: "#6A4FB3" },
  { key: "blue", label: "Blue", tailwind: "bg-blue-600", hex: "#1976D2" },
  { key: "red", label: "Red", tailwind: "bg-red-700", hex: "#C62828" },
  { key: "amber", label: "Amber", tailwind: "bg-amber-600", hex: "#F9A825" },
  { key: "indigo", label: "Indigo", tailwind: "bg-indigo-600", hex: "#3949AB" },
  { key: "pink", label: "Pink", tailwind: "bg-pink-700", hex: "#AD1457" },
  { key: "cyan", label: "Cyan", tailwind: "bg-cyan-600", hex: "#0097A7" },
];

function getColorClass(colorKey: string): string {
  return COLOR_PALETTE.find((c) => c.key === colorKey)?.tailwind ?? "bg-purple-600";
}

function getIconComponent(iconName: string): LucideIcon {
  return ICON_MAP[iconName] || Settings;
}

// ── Sortable card component ──────────────────────────

function SortableCard({
  module,
  onIconTap,
  onColorTap,
}: {
  module: DashboardModule;
  onIconTap: (module: DashboardModule) => void;
  onColorTap: (module: DashboardModule) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: module.key });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const IconComp = getIconComponent(module.iconName);
  const colorKey = module.colorKey || (module.key === "dine_in" ? "green" : module.key === "to_go" ? "orange" : module.key === "bar" ? "teal" : "purple");
  const bg = getColorClass(colorKey);

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`relative group ${isDragging ? "z-50 opacity-80" : ""}`}
    >
      <div
        className={`${bg} rounded-2xl p-4 flex flex-col items-center justify-center aspect-square text-white cursor-grab active:cursor-grabbing transition-shadow ${
          isDragging ? "shadow-2xl ring-2 ring-blue-400" : "shadow-sm hover:shadow-lg"
        }`}
        {...attributes}
        {...listeners}
      >
        <GripVertical
          size={14}
          className="absolute top-2 left-1/2 -translate-x-1/2 opacity-40 group-hover:opacity-80 transition-opacity"
        />
        <IconComp size={32} className="mb-2" />
        <span className="text-[11px] font-semibold tracking-wide text-center leading-tight">
          {module.label}
        </span>
      </div>
      <button
        onClick={(e) => {
          e.stopPropagation();
          onIconTap(module);
        }}
        className="absolute -bottom-1 -right-1 w-7 h-7 bg-white rounded-full shadow-md border border-slate-200 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity hover:bg-blue-50"
        title="Change icon"
      >
        <SlidersHorizontal size={13} className="text-blue-600" />
      </button>
      <button
        onClick={(e) => {
          e.stopPropagation();
          onColorTap(module);
        }}
        className="absolute -bottom-1 -left-1 w-7 h-7 bg-white rounded-full shadow-md border border-slate-200 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity hover:bg-blue-50"
        title="Change color"
      >
        <Palette size={13} className="text-blue-600" />
      </button>
    </div>
  );
}

// ── Icon picker modal with search ────────────────────

function IconPickerModal({
  module,
  onSelect,
  onClose,
}: {
  module: DashboardModule;
  onSelect: (iconName: string) => void;
  onClose: () => void;
}) {
  const [search, setSearch] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const t = setTimeout(() => inputRef.current?.focus(), 100);
    return () => clearTimeout(t);
  }, []);

  const filtered = useMemo(() => {
    if (!search.trim()) return SEARCHABLE_ICONS;
    const q = search.toLowerCase().trim();
    return SEARCHABLE_ICONS.filter(
      (icon) =>
        icon.label.toLowerCase().includes(q) ||
        icon.name.toLowerCase().replace(/_/g, " ").includes(q) ||
        icon.tags.some((tag) => tag.includes(q))
    );
  }, [search]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden flex flex-col max-h-[85vh]">
        {/* Header */}
        <div className="flex items-center justify-between p-5 border-b border-slate-100 shrink-0">
          <h3 className="font-semibold text-slate-800">
            Select Icon for <span className="text-blue-600">{module.label}</span>
          </h3>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Search */}
        <div className="px-5 pt-4 pb-2 shrink-0">
          <div className="relative">
            <Search
              size={16}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
            />
            <input
              ref={inputRef}
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search icons… (e.g. food, money, inventory)"
              className="w-full pl-9 pr-9 py-2.5 rounded-xl border border-slate-200 bg-slate-50 text-sm text-slate-700 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all"
            />
            {search && (
              <button
                onClick={() => setSearch("")}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 transition-colors"
              >
                <X size={14} />
              </button>
            )}
          </div>
        </div>

        {/* Icon grid */}
        <div className="px-5 pb-3 pt-1 overflow-y-auto flex-1 min-h-0">
          {filtered.length === 0 ? (
            <div className="py-12 text-center">
              <Search size={32} className="mx-auto mb-3 text-slate-300" />
              <p className="text-sm text-slate-400">
                No icons found for &ldquo;{search}&rdquo;
              </p>
              <p className="text-xs text-slate-300 mt-1">Try a different keyword</p>
            </div>
          ) : (
            <div className="grid grid-cols-6 gap-2">
              {filtered.map((icon) => {
                const isSelected = icon.name === module.iconName;
                return (
                  <button
                    key={icon.name}
                    onClick={() => onSelect(icon.name)}
                    className={`relative flex flex-col items-center gap-1.5 p-3 rounded-xl transition-all duration-150 ${
                      isSelected
                        ? "bg-blue-50 ring-2 ring-blue-500 text-blue-600 shadow-sm"
                        : "hover:bg-slate-50 hover:shadow-sm hover:scale-105 text-slate-600"
                    }`}
                  >
                    <icon.Icon size={24} />
                    <span className="text-[9px] font-medium leading-tight text-center truncate w-full">
                      {icon.label}
                    </span>
                    {isSelected && (
                      <Check
                        size={10}
                        className="absolute top-1.5 right-1.5 text-blue-500"
                      />
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-slate-100 flex items-center justify-between shrink-0">
          <span className="text-xs text-slate-400">
            {filtered.length} icon{filtered.length !== 1 ? "s" : ""}
            {search && ` matching "${search}"`}
          </span>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100 rounded-xl transition-colors"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Color picker modal ───────────────────────────────

function ColorPickerModal({
  module,
  onSelect,
  onClose,
}: {
  module: DashboardModule;
  onSelect: (colorKey: string) => void;
  onClose: () => void;
}) {
  const colorKey = module.colorKey || "purple";
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden">
        <div className="flex items-center justify-between p-5 border-b border-slate-100">
          <h3 className="font-semibold text-slate-800">
            Select Color for <span className="text-blue-600">{module.label}</span>
          </h3>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
          >
            <X size={18} />
          </button>
        </div>
        <div className="p-5 grid grid-cols-5 gap-3">
          {COLOR_PALETTE.map((color) => {
            const isSelected = color.key === colorKey;
            return (
              <button
                key={color.key}
                onClick={() => onSelect(color.key)}
                className={`flex flex-col items-center gap-1.5 p-3 rounded-xl transition-all ${
                  isSelected
                    ? "ring-2 ring-slate-800 ring-offset-2"
                    : "hover:ring-2 hover:ring-slate-300"
                }`}
              >
                <div
                  className="w-10 h-10 rounded-xl shadow-inner"
                  style={{ backgroundColor: color.hex }}
                />
                <span className="text-[10px] font-medium text-slate-600">{color.label}</span>
                {isSelected && <Check size={12} className="text-green-600" />}
              </button>
            );
          })}
        </div>
        <div className="p-4 border-t border-slate-100 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100 rounded-xl transition-colors"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main page ────────────────────────────────────────

type SaveStatus = "idle" | "saving" | "saved" | "error";

export default function CustomizeDashboardPage() {
  const [modules, setModules] = useState<DashboardModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [pickerTarget, setPickerTarget] = useState<DashboardModule | null>(null);
  const [colorPickerTarget, setColorPickerTarget] = useState<DashboardModule | null>(null);
  const [hasChanges, setHasChanges] = useState(false);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  useEffect(() => {
    const unsub = onSnapshot(
      doc(db, "Settings", "dashboard"),
      (snap) => {
        if (snap.exists()) {
          const data = snap.data();
          const raw = (data.modules || []) as DashboardModule[];
          if (raw.length > 0) {
            const normalized = raw.map((m, i) => ({
              ...m,
              colorKey: m.colorKey ?? (m.key === "dine_in" ? "green" : m.key === "to_go" ? "orange" : m.key === "bar" ? "teal" : "purple"),
              position: m.position ?? i,
            })).sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
            if (!hasChanges) setModules(normalized);
            setLoading(false);
            return;
          }
        }
        if (!hasChanges) setModules(DEFAULT_MODULES);
        setLoading(false);
      },
      (err) => {
        console.error("Failed to listen to dashboard config:", err);
        setModules(DEFAULT_MODULES);
        setLoading(false);
      }
    );
    return () => unsub();
  }, [hasChanges]);

  const handleSave = useCallback(async () => {
    if (!hasChanges) return;
    setSaveStatus("saving");
    try {
      const indexed = modules.map((m, i) => ({
        key: m.key,
        label: m.label,
        iconName: m.iconName,
        colorKey: m.colorKey || (m.key === "dine_in" ? "green" : m.key === "to_go" ? "orange" : m.key === "bar" ? "teal" : "purple"),
        position: i,
      }));
      await setDoc(doc(db, "Settings", "dashboard"), { modules: indexed });
      setHasChanges(false);
      setSaveStatus("saved");
      setTimeout(() => setSaveStatus("idle"), 3000);
    } catch (err) {
      console.error("Failed to save dashboard config:", err);
      setSaveStatus("error");
      setTimeout(() => setSaveStatus("idle"), 4000);
    }
  }, [modules, hasChanges]);

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    setModules((prev) => {
      const oldIdx = prev.findIndex((m) => m.key === active.id);
      const newIdx = prev.findIndex((m) => m.key === over.id);
      return arrayMove(prev, oldIdx, newIdx);
    });
    setHasChanges(true);
  };

  const handleIconSelect = (iconName: string) => {
    if (!pickerTarget) return;
    setModules((prev) =>
      prev.map((m) => (m.key === pickerTarget.key ? { ...m, iconName } : m))
    );
    setPickerTarget(null);
    setHasChanges(true);
  };

  const handleColorSelect = (colorKey: string) => {
    if (!colorPickerTarget) return;
    setModules((prev) =>
      prev.map((m) => (m.key === colorPickerTarget.key ? { ...m, colorKey } : m))
    );
    setColorPickerTarget(null);
    setHasChanges(true);
  };

  const handleReset = () => {
    setModules(DEFAULT_MODULES);
    setHasChanges(true);
  };

  if (loading) {
    return (
      <>
        <Header title="Customize Dashboard" />
        <div className="p-6 flex items-center justify-center h-64">
          <div className="animate-spin w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full" />
        </div>
      </>
    );
  }

  return (
    <>
      <Header title="Customize Dashboard" />
      <div className="p-6 space-y-6">
        {/* Instructions */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-5">
          <div className="flex items-start gap-3">
            <div className="p-2 rounded-xl bg-blue-50">
              <LayoutGrid size={20} className="text-blue-600" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-800">POS Dashboard Layout</h3>
              <p className="text-sm text-slate-500 mt-1">
                Drag to reorder buttons. Hover and click the icon badge to change an
                icon, or the color badge to change the button color. Click <strong>Save Layout</strong> to sync to the POS app.
              </p>
            </div>
          </div>
        </div>

        {/* Grid preview */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
          <h3 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-5">
            Button Layout — 3-column grid
          </h3>

          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext
              items={modules.map((m) => m.key)}
              strategy={rectSortingStrategy}
            >
              <div className="grid grid-cols-3 gap-4 max-w-md">
                {modules.map((module) => (
                  <SortableCard
                    key={module.key}
                    module={module}
                    onIconTap={setPickerTarget}
                    onColorTap={setColorPickerTarget}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        </div>

        {/* Actions */}
        <div className="flex items-center justify-between">
          <button
            onClick={handleReset}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-medium text-slate-600 border border-slate-200 hover:bg-slate-50 transition-colors"
          >
            <RotateCcw size={16} />
            Reset Defaults
          </button>

          <button
            onClick={handleSave}
            disabled={saveStatus === "saving" || !hasChanges}
            className={`flex items-center gap-2 px-6 py-2.5 rounded-xl text-sm font-semibold text-white transition-all ${
              saveStatus === "saving" || !hasChanges
                ? "bg-slate-300 cursor-not-allowed"
                : "bg-blue-600 hover:bg-blue-700 shadow-sm hover:shadow-md"
            }`}
          >
            <Save size={16} />
            {saveStatus === "saving" ? "Saving..." : "Save Layout"}
          </button>
        </div>

        {/* Save status feedback */}
        <div className="text-center min-h-[20px]">
          {saveStatus === "saving" && (
            <p className="text-xs text-slate-500 font-medium">Saving to Firebase...</p>
          )}
          {saveStatus === "saved" && (
            <p className="text-xs text-green-600 font-medium flex items-center justify-center gap-1">
              <Check size={14} />
              Saved! Changes will sync to the POS app.
            </p>
          )}
          {saveStatus === "error" && (
            <p className="text-xs text-red-600 font-medium">
              Save failed. Check your connection and try again.
            </p>
          )}
          {hasChanges && saveStatus === "idle" && (
            <p className="text-xs text-amber-600 font-medium">
              You have unsaved changes — click Save Layout to sync to the app.
            </p>
          )}
        </div>
      </div>

      {/* Icon picker modal */}
      {pickerTarget && (
        <IconPickerModal
          module={pickerTarget}
          onSelect={handleIconSelect}
          onClose={() => setPickerTarget(null)}
        />
      )}

      {/* Color picker modal */}
      {colorPickerTarget && (
        <ColorPickerModal
          module={colorPickerTarget}
          onSelect={handleColorSelect}
          onClose={() => setColorPickerTarget(null)}
        />
      )}
    </>
  );
}
