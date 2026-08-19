import java.util.Scanner;

public class Main
{
    public static void main(String[] args) throws Exception
    {
        Scanner scanner0 = new Scanner(System.in);

        System.out.println("===== SAT TUTORING =====");
        System.out.println("Choose command:");
        System.out.println("help");
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

        else
        {
            System.out.println("Unknown command: " + prompt0);
        }

        scanner0.close();
    }
}
