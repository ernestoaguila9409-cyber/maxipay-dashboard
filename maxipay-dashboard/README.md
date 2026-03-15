# MaxiPay Dashboard

Web dashboard for MaxiPay restaurant POS system. Monitor sales, orders, menu items, employees, and reports.

## Tech Stack

- **Next.js** (App Router)
- **React** + **TypeScript**
- **Firebase** (Auth + Firestore)
- **Tailwind CSS**
- **Lucide React** (icons)

## Getting Started

### 1. Install dependencies

```bash
cd maxipay-dashboard
npm install
```

### 2. Configure Firebase

Copy the example env file and fill in your Firebase credentials:

```bash
cp .env.local.example .env.local
```

Edit `.env.local` with your Firebase project config values.

### 3. Run the dev server

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

## Project Structure

```
src/
├── app/
│   ├── login/             # Login page
│   ├── dashboard/         # Protected dashboard routes
│   │   ├── orders/        # Orders list
│   │   ├── menu/          # Menu items
│   │   ├── employees/     # Employee management
│   │   ├── reports/       # Sales reports
│   │   └── settings/      # Settings
│   ├── layout.tsx         # Root layout
│   └── page.tsx           # Root redirect
├── components/
│   ├── Header.tsx         # Top navigation bar
│   ├── Sidebar.tsx        # Side navigation
│   ├── StatCard.tsx       # Metric cards
│   └── OrdersTable.tsx    # Orders table
├── context/
│   └── AuthContext.tsx     # Firebase auth state
└── firebase/
    └── firebaseConfig.ts  # Firebase initialization
```

## Firestore Collections

All queries filter by `merchantId` for multi-tenant support.

- **orders** — order data (total, tip, status, orderType, createdAt)
- **menuItems** — menu items (name, price, category, available)
- **employees** — staff (name, role, email, phone, active)
