"use client";

import { useEffect, useState } from "react";
import { collection, query, where, getDocs } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import { UserPlus, Mail, Phone } from "lucide-react";

interface Employee {
  id: string;
  name: string;
  role: string;
  email: string;
  phone: string;
  active: boolean;
}

export default function EmployeesPage() {
  const { user } = useAuth();
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) return;

    const fetchEmployees = async () => {
      try {
        const empRef = collection(db, "employees");
        const q = query(empRef, where("merchantId", "==", user.uid));
        const snapshot = await getDocs(q);

        const list: Employee[] = [];
        snapshot.forEach((doc) => {
          const data = doc.data();
          list.push({
            id: doc.id,
            name: data.name || "Unknown",
            role: data.role || "Staff",
            email: data.email || "",
            phone: data.phone || "",
            active: data.active !== false,
          });
        });

        setEmployees(list);
      } catch (error) {
        console.error("Error fetching employees:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchEmployees();
  }, [user]);

  return (
    <>
      <Header title="Employees" />
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between">
          <p className="text-slate-500 text-sm">
            {employees.length} employee{employees.length !== 1 ? "s" : ""}
          </p>
          <button className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors">
            <UserPlus size={16} />
            Add Employee
          </button>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : employees.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <p className="text-slate-400 text-lg">No employees found</p>
            <p className="text-slate-400 text-sm mt-1">
              Employee data from your POS will appear here
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {employees.map((emp) => (
              <div
                key={emp.id}
                className="bg-white rounded-2xl p-5 shadow-sm border border-slate-100 hover:shadow-md transition-shadow"
              >
                <div className="flex items-center gap-4 mb-4">
                  <div className="w-12 h-12 rounded-full bg-blue-100 flex items-center justify-center">
                    <span className="text-blue-600 font-semibold text-lg">
                      {emp.name.charAt(0)}
                    </span>
                  </div>
                  <div>
                    <h3 className="font-semibold text-slate-800">{emp.name}</h3>
                    <p className="text-sm text-slate-500">{emp.role}</p>
                  </div>
                  <span
                    className={`ml-auto text-xs px-2 py-0.5 rounded-full font-medium ${
                      emp.active
                        ? "bg-emerald-50 text-emerald-700"
                        : "bg-slate-100 text-slate-500"
                    }`}
                  >
                    {emp.active ? "Active" : "Inactive"}
                  </span>
                </div>
                {emp.email && (
                  <div className="flex items-center gap-2 text-sm text-slate-500 mb-1">
                    <Mail size={14} />
                    {emp.email}
                  </div>
                )}
                {emp.phone && (
                  <div className="flex items-center gap-2 text-sm text-slate-500">
                    <Phone size={14} />
                    {emp.phone}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
