"use client";

import { useEffect, useState } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  query,
  where,
  getDocs,
  writeBatch,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  Search,
  Plus,
  Pencil,
  Trash2,
  X,
  AlertTriangle,
  ChevronRight,
} from "lucide-react";

interface ModifierGroup {
  id: string;
  name: string;
  required: boolean;
  maxSelection: number;
  groupType: string; // "ADD" | "REMOVE"
}

interface ModifierOption {
  id: string;
  name: string;
  price: number;
  groupId: string;
}

export default function ModifiersPage() {
  const { user } = useAuth();
  const [groups, setGroups] = useState<ModifierGroup[]>([]);
  const [options, setOptions] = useState<ModifierOption[]>([]);
  const [selectedGroup, setSelectedGroup] = useState<ModifierGroup | null>(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  // Group modal state
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<ModifierGroup | null>(null);
  const [groupName, setGroupName] = useState("");
  const [groupRequired, setGroupRequired] = useState(false);
  const [groupMaxSelection, setGroupMaxSelection] = useState("1");
  const [groupIsRemove, setGroupIsRemove] = useState(false);
  const [groupSaving, setGroupSaving] = useState(false);

  // Option modal state
  const [optionModalOpen, setOptionModalOpen] = useState(false);
  const [editingOption, setEditingOption] = useState<ModifierOption | null>(null);
  const [optionName, setOptionName] = useState("");
  const [optionPrice, setOptionPrice] = useState("");
  const [optionSaving, setOptionSaving] = useState(false);

  // Delete state
  const [deleteGroupTarget, setDeleteGroupTarget] = useState<ModifierGroup | null>(null);
  const [deleteOptionTarget, setDeleteOptionTarget] = useState<ModifierOption | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Load groups in realtime
  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, "ModifierGroups"), (snap) => {
      const list: ModifierGroup[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          required: data.required ?? false,
          maxSelection: data.maxSelection ?? 1,
          groupType: data.groupType ?? "ADD",
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setGroups(list);
      setLoading(false);

      if (selectedGroup) {
        const updated = list.find((g) => g.id === selectedGroup.id);
        if (updated) setSelectedGroup(updated);
        else setSelectedGroup(null);
      }
    });
    return () => unsub();
  }, [user]); // eslint-disable-line react-hooks/exhaustive-deps

  // Load options when group is selected
  useEffect(() => {
    if (!selectedGroup) {
      setOptions([]);
      return;
    }
    const q = query(
      collection(db, "ModifierOptions"),
      where("groupId", "==", selectedGroup.id)
    );
    const unsub = onSnapshot(q, (snap) => {
      const list: ModifierOption[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          price: data.price ?? 0,
          groupId: data.groupId ?? "",
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setOptions(list);
    });
    return () => unsub();
  }, [selectedGroup?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const filteredOptions = options.filter(
    (o) => !search || o.name.toLowerCase().includes(search.toLowerCase())
  );

  // ── Group CRUD ──

  const openAddGroup = () => {
    setEditingGroup(null);
    setGroupName("");
    setGroupRequired(false);
    setGroupMaxSelection("1");
    setGroupIsRemove(false);
    setGroupModalOpen(true);
  };

  const openEditGroup = (g: ModifierGroup) => {
    setEditingGroup(g);
    setGroupName(g.name);
    setGroupRequired(g.required);
    setGroupMaxSelection(String(g.maxSelection));
    setGroupIsRemove(g.groupType === "REMOVE");
    setGroupModalOpen(true);
  };

  const handleSaveGroup = async () => {
    const name = groupName.trim();
    if (!name) return;
    const maxSelection = parseInt(groupMaxSelection, 10) || 1;
    const groupType = groupIsRemove ? "REMOVE" : "ADD";

    setGroupSaving(true);
    try {
      const data = { name, required: groupRequired, maxSelection, groupType };
      if (editingGroup) {
        await updateDoc(doc(db, "ModifierGroups", editingGroup.id), data);
      } else {
        await addDoc(collection(db, "ModifierGroups"), data);
      }
      setGroupModalOpen(false);
    } catch (err) {
      console.error("Failed to save group:", err);
    } finally {
      setGroupSaving(false);
    }
  };

  const handleDeleteGroup = async () => {
    if (!deleteGroupTarget) return;
    setDeleting(true);
    try {
      const batch = writeBatch(db);
      batch.delete(doc(db, "ModifierGroups", deleteGroupTarget.id));
      const optSnap = await getDocs(
        query(collection(db, "ModifierOptions"), where("groupId", "==", deleteGroupTarget.id))
      );
      optSnap.forEach((d) => batch.delete(d.ref));
      const linkSnap = await getDocs(
        query(collection(db, "ItemModifierGroups"), where("groupId", "==", deleteGroupTarget.id))
      );
      linkSnap.forEach((d) => batch.delete(d.ref));
      await batch.commit();
      if (selectedGroup?.id === deleteGroupTarget.id) setSelectedGroup(null);
    } catch (err) {
      console.error("Failed to delete group:", err);
    } finally {
      setDeleting(false);
      setDeleteGroupTarget(null);
    }
  };

  // ── Option CRUD ──

  const openAddOption = () => {
    setEditingOption(null);
    setOptionName("");
    setOptionPrice("");
    setOptionModalOpen(true);
  };

  const openEditOption = (o: ModifierOption) => {
    setEditingOption(o);
    setOptionName(o.name);
    setOptionPrice(o.price.toFixed(2));
    setOptionModalOpen(true);
  };

  const handleSaveOption = async () => {
    if (!selectedGroup) return;
    const name = optionName.trim();
    if (!name) return;
    const price = selectedGroup.groupType === "REMOVE" ? 0 : parseFloat(optionPrice) || 0;

    setOptionSaving(true);
    try {
      if (editingOption) {
        await updateDoc(doc(db, "ModifierOptions", editingOption.id), { name, price });
      } else {
        await addDoc(collection(db, "ModifierOptions"), {
          name,
          price,
          groupId: selectedGroup.id,
        });
      }
      setOptionModalOpen(false);
    } catch (err) {
      console.error("Failed to save option:", err);
    } finally {
      setOptionSaving(false);
    }
  };

  const handleDeleteOption = async () => {
    if (!deleteOptionTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "ModifierOptions", deleteOptionTarget.id));
    } catch (err) {
      console.error("Failed to delete option:", err);
    } finally {
      setDeleting(false);
      setDeleteOptionTarget(null);
    }
  };

  return (
    <>
      <Header title="Modifiers" />
      <div className="p-6 h-[calc(100vh-64px)] flex flex-col">
        {loading ? (
          <div className="flex items-center justify-center flex-1">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <div className="flex gap-6 flex-1 min-h-0">
            {/* ── Left Panel: Groups ── */}
            <div className="w-72 flex-shrink-0 flex flex-col bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
              <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
                <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">
                  Groups
                </h2>
                <button
                  onClick={openAddGroup}
                  className="p-1.5 rounded-lg text-blue-600 hover:bg-blue-50 transition-colors"
                  title="Add group"
                >
                  <Plus size={18} />
                </button>
              </div>
              <div className="flex-1 overflow-y-auto p-2">
                {groups.length === 0 ? (
                  <p className="text-sm text-slate-400 text-center py-8">
                    No modifier groups yet
                  </p>
                ) : (
                  groups.map((g) => {
                    const isActive = selectedGroup?.id === g.id;
                    return (
                      <div
                        key={g.id}
                        onClick={() => {
                          setSelectedGroup(g);
                          setSearch("");
                        }}
                        role="button"
                        tabIndex={0}
                        className={`w-full text-left px-4 py-3 rounded-xl mb-1 transition-all group/item cursor-pointer ${
                          isActive
                            ? "bg-blue-50 border border-blue-200"
                            : "hover:bg-slate-50 border border-transparent"
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <div className="min-w-0 flex-1">
                            <p
                              className={`text-sm font-medium truncate ${
                                isActive ? "text-blue-700" : "text-slate-700"
                              }`}
                            >
                              {g.name}
                            </p>
                            <p
                              className={`text-xs mt-0.5 ${
                                g.groupType === "REMOVE"
                                  ? "text-red-400"
                                  : "text-slate-400"
                              }`}
                            >
                              {g.groupType === "REMOVE"
                                ? "Remove ingredients"
                                : `Add-ons · max ${g.maxSelection}`}
                              {g.required && " · Required"}
                            </p>
                          </div>
                          <div className="flex items-center gap-0.5 opacity-0 group-hover/item:opacity-100 transition-opacity ml-2 flex-shrink-0">
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                openEditGroup(g);
                              }}
                              className="p-1 rounded-md hover:bg-blue-100 text-slate-400 hover:text-blue-600 transition-colors"
                              title="Edit group"
                            >
                              <Pencil size={13} />
                            </button>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setDeleteGroupTarget(g);
                              }}
                              className="p-1 rounded-md hover:bg-red-100 text-slate-400 hover:text-red-500 transition-colors"
                              title="Delete group"
                            >
                              <Trash2 size={13} />
                            </button>
                          </div>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>

            {/* ── Right Panel: Options ── */}
            <div className="flex-1 flex flex-col bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden min-w-0">
              {!selectedGroup ? (
                <div className="flex-1 flex flex-col items-center justify-center text-slate-400">
                  <ChevronRight size={40} className="mb-3 text-slate-300" />
                  <p className="text-lg font-medium">Select a group</p>
                  <p className="text-sm mt-1">
                    Choose a modifier group from the left to see its options
                  </p>
                </div>
              ) : (
                <>
                  {/* Options header */}
                  <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 gap-4">
                    <div className="min-w-0">
                      <h2 className="text-base font-semibold text-slate-800 truncate">
                        {selectedGroup.name}
                      </h2>
                      <p className="text-xs text-slate-400 mt-0.5">
                        {filteredOptions.length} option
                        {filteredOptions.length !== 1 ? "s" : ""}
                        {selectedGroup.groupType === "REMOVE" && (
                          <span className="text-red-400 ml-1">· Remove group</span>
                        )}
                      </p>
                    </div>
                    <div className="flex items-center gap-3 flex-shrink-0">
                      <div className="relative">
                        <Search
                          size={15}
                          className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                        />
                        <input
                          type="text"
                          placeholder="Search modifiers..."
                          value={search}
                          onChange={(e) => setSearch(e.target.value)}
                          className="pl-9 pr-4 py-2 rounded-xl bg-slate-50 border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 w-60"
                        />
                      </div>
                      <button
                        onClick={openAddOption}
                        className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors flex-shrink-0"
                      >
                        <Plus size={16} />
                        Add Option
                      </button>
                    </div>
                  </div>

                  {/* Options list */}
                  <div className="flex-1 overflow-y-auto">
                    {filteredOptions.length === 0 ? (
                      <div className="flex flex-col items-center justify-center py-16 text-slate-400">
                        <p className="text-base font-medium">No options</p>
                        <p className="text-sm mt-1">
                          {search
                            ? "No options match your search"
                            : "Add options to this modifier group"}
                        </p>
                      </div>
                    ) : (
                      <table className="w-full">
                        <thead>
                          <tr className="border-b border-slate-100">
                            <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-5 py-3">
                              Option Name
                            </th>
                            {selectedGroup.groupType !== "REMOVE" && (
                              <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-5 py-3 w-32">
                                Price
                              </th>
                            )}
                            <th className="text-right text-xs font-medium text-slate-400 uppercase tracking-wider px-5 py-3 w-28">
                              Actions
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {filteredOptions.map((opt) => (
                            <tr
                              key={opt.id}
                              className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors group/row"
                            >
                              <td className="px-5 py-3.5">
                                <span
                                  className={`text-sm font-medium ${
                                    selectedGroup.groupType === "REMOVE"
                                      ? "text-red-600"
                                      : "text-slate-800"
                                  }`}
                                >
                                  {opt.name}
                                </span>
                              </td>
                              {selectedGroup.groupType !== "REMOVE" && (
                                <td className="px-5 py-3.5">
                                  <span className="text-sm text-slate-500">
                                    +${opt.price.toFixed(2)}
                                  </span>
                                </td>
                              )}
                              <td className="px-5 py-3.5 text-right">
                                <div className="flex items-center justify-end gap-1 opacity-0 group-hover/row:opacity-100 transition-opacity">
                                  <button
                                    onClick={() => openEditOption(opt)}
                                    className="p-1.5 rounded-lg text-slate-400 hover:bg-blue-50 hover:text-blue-600 transition-colors"
                                    title="Edit option"
                                  >
                                    <Pencil size={15} />
                                  </button>
                                  <button
                                    onClick={() => setDeleteOptionTarget(opt)}
                                    className="p-1.5 rounded-lg text-slate-400 hover:bg-red-50 hover:text-red-500 transition-colors"
                                    title="Delete option"
                                  >
                                    <Trash2 size={15} />
                                  </button>
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </div>

      {/* ── Group Modal (Add / Edit) ── */}
      {groupModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !groupSaving && setGroupModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {editingGroup ? "Edit Group" : "Add Modifier Group"}
                </h3>
                <button
                  onClick={() => setGroupModalOpen(false)}
                  disabled={groupSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Group Name
                  </label>
                  <input
                    type="text"
                    value={groupName}
                    onChange={(e) => setGroupName(e.target.value)}
                    placeholder="e.g. Size, Toppings, Sauces"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Max Selections
                  </label>
                  <input
                    type="number"
                    min="1"
                    value={groupMaxSelection}
                    onChange={(e) => setGroupMaxSelection(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div className="space-y-3">
                  <label className="flex items-center gap-2.5 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={groupRequired}
                      onChange={(e) => setGroupRequired(e.target.checked)}
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-700">Required</span>
                  </label>
                  <label className="flex items-center gap-2.5 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={groupIsRemove}
                      onChange={(e) => setGroupIsRemove(e.target.checked)}
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-700">
                      Remove Ingredients Group
                    </span>
                  </label>
                </div>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setGroupModalOpen(false)}
                  disabled={groupSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveGroup}
                  disabled={groupSaving || !groupName.trim()}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {groupSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : editingGroup ? (
                    "Save Changes"
                  ) : (
                    "Add Group"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Option Modal (Add / Edit) ── */}
      {optionModalOpen && selectedGroup && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !optionSaving && setOptionModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-lg font-semibold text-slate-800">
                    {editingOption ? "Edit Option" : "Add Option"}
                  </h3>
                  <p className="text-xs text-slate-400 mt-0.5">
                    {selectedGroup.name}
                  </p>
                </div>
                <button
                  onClick={() => setOptionModalOpen(false)}
                  disabled={optionSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Option Name
                  </label>
                  <input
                    type="text"
                    value={optionName}
                    onChange={(e) => setOptionName(e.target.value)}
                    placeholder="e.g. Small, Medium, Large"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                {selectedGroup.groupType !== "REMOVE" && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Price Adjustment ($)
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={optionPrice}
                      onChange={(e) => setOptionPrice(e.target.value)}
                      placeholder="0.00"
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                )}
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setOptionModalOpen(false)}
                  disabled={optionSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveOption}
                  disabled={optionSaving || !optionName.trim()}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {optionSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : editingOption ? (
                    "Save Changes"
                  ) : (
                    "Add Option"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete Group Confirmation ── */}
      {deleteGroupTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deleting && setDeleteGroupTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Group
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {deleteGroupTarget.name}
                </strong>
                ? This will also delete all its options and remove assignments from menu items.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteGroupTarget(null)}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteGroup}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete Option Confirmation ── */}
      {deleteOptionTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deleting && setDeleteOptionTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Option
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {deleteOptionTarget.name}
                </strong>
                ? This cannot be undone.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteOptionTarget(null)}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteOption}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
