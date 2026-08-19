import java.util.Scanner;

public class Main
{
    public static void main(String[] args) throws Exception
    {
        Scanner scanner0 = new Scanner(System.in);

        System.out.println("===== SAT TUTORING =====");
        System.out.println("Choose command:");
        System.out.println("help");
        System.out.println("serve");
        System.out.println("exit");
        System.out.println();
        System.out.print("Enter command: ");

        String prompt0 = scanner0.nextLine().trim();

        // ============================================================
        // 1. HELP
        // ============================================================

        if (prompt0.equalsIgnoreCase("help") || prompt0.equals("1"))
        {
            System.out.println("Commands are added here as workflows are built.");
            System.out.println("Each workflow gets its own block below, in this menu style.");
        }

        // ============================================================
        // 2. EXIT
        // ============================================================

        else if (prompt0.equalsIgnoreCase("exit") || prompt0.equals("2"))
        {
            System.out.println("Goodbye.");
        }

        // ============================================================
        // 3. SERVE (web app)
        // ============================================================

        else if (prompt0.equalsIgnoreCase("serve") || prompt0.equals("3"))
        {
            String dbPath = satapp.db.Database.resolveDbPath();
            int port = satapp.web.WebServer.resolvePort();
            System.out.println("Using database: " + dbPath);
            System.out.println("Starting web server on http://localhost:" + port);

            io.javalin.Javalin app = satapp.web.WebServer.start(dbPath, port);

            System.out.println("Server is running. Press Enter to stop.");
            scanner0.nextLine();

            app.stop();
            System.out.println("Server stopped.");
        }

        else
        {
            System.out.println("Unknown command: " + prompt0);
        }

        scanner0.close();
    }
}
