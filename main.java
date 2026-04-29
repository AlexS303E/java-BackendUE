import java.util.Scanner;

public class main  {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.print("Введите ваше имя: ");
        String name = scanner.nextLine();
        
        System.out.println("Привет, " + name + "! Добро пожаловать в мир Java.");
        
        scanner.close();
    }
}