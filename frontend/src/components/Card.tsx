export default function Card({
  className,
  children,
}: {
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={`rounded-lg border border-bdr bg-card p-6 ${className ?? ""}`}>
      {children}
    </section>
  );
}
