import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class CheckPw {
  public static void main(String[] args) {
    String hash = "$2a$10$fD0tBRx.6SEhTNwnMalu2eBw/yWk3H30ZO9qnOWxO.8OWAfjs95EK";
    String[] candidates = {"root","Admin@123","admin123","123456","Abcde123!","admin","password","admin@123","Admin@123!","smashhub","root123","12345678","test123"};
    BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    for (String c : candidates) {
      System.out.println(c + " => " + enc.matches(c, hash));
    }
  }
}
