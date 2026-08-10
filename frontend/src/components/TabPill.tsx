export default function TabPill({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`rounded-full px-4 py-2 text-sm font-medium transition-colors ${
        active
          ? "bg-t1 text-white"
          : "border border-bdr text-t2 hover:bg-hov"
      }`}
    >
      {children}
    </button>
  );
}
