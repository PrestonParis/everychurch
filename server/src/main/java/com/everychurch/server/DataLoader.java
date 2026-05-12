package com.everychurch.server;

import com.everychurch.server.model.Church;
import com.everychurch.server.model.ServiceTime;
import com.everychurch.server.repository.ChurchRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataLoader implements CommandLineRunner {

    private final ChurchRepository repo;

    public DataLoader(ChurchRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) return;

        // St. Mary's Catholic Church
        Church stMarys = new Church();
        stMarys.setName("St. Mary's Catholic Church");
        stMarys.setDenomination("Catholic");
        stMarys.setAddress("2011 N Orleans St");
        stMarys.setCity("Chicago");
        stMarys.setState("IL");
        stMarys.setZipCode("60614");
        stMarys.setPhone("(312) 642-3798");
        stMarys.setEmail("info@stmarys-chicago.org");
        stMarys.setWebsite("https://stmarys-chicago.org");
        stMarys.setDescription("A vibrant Catholic community in Lincoln Park, Chicago. We welcome all to worship, grow, and serve together in faith.");
        stMarys.setPastor("Fr. Michael O'Brien");
        stMarys.setFacebook("https://facebook.com/stmaryschicago");
        addServiceTime(stMarys, "Sunday", "08:00", "Mass", null);
        addServiceTime(stMarys, "Sunday", "10:30", "Mass", null);
        addServiceTime(stMarys, "Sunday", "12:30", "Mass", "Spanish");
        addServiceTime(stMarys, "Wednesday", "18:30", "Mass", null);
        repo.save(stMarys);

        // Hillside Community Church
        Church hillside = new Church();
        hillside.setName("Hillside Community Church");
        hillside.setDenomination("Non-denominational");
        hillside.setAddress("4500 W Fullerton Ave");
        hillside.setCity("Chicago");
        hillside.setState("IL");
        hillside.setZipCode("60639");
        hillside.setPhone("(773) 745-0808");
        hillside.setWebsite("https://hillsidechicago.com");
        hillside.setDescription("A modern, welcoming church for people of all backgrounds. Our mission is to love God, love people, and serve the city of Chicago.");
        hillside.setPastor("Pastor David Kim");
        hillside.setInstagram("https://instagram.com/hillsidechicago");
        hillside.setYoutube("https://youtube.com/@hillsidechicago");
        addServiceTime(hillside, "Sunday", "09:00", "Sunday Service", null);
        addServiceTime(hillside, "Sunday", "11:00", "Sunday Service", null);
        addServiceTime(hillside, "Wednesday", "19:00", "Bible Study", null);
        repo.save(hillside);

        // Calvary Baptist Church
        Church calvary = new Church();
        calvary.setName("Calvary Baptist Church");
        calvary.setDenomination("Baptist");
        calvary.setAddress("1020 S Indiana Ave");
        calvary.setCity("Chicago");
        calvary.setState("IL");
        calvary.setZipCode("60605");
        calvary.setPhone("(312) 842-9610");
        calvary.setEmail("office@calvarybaptist.org");
        calvary.setDescription("Serving the South Loop community with the Gospel since 1955. Join us for worship, discipleship, and community outreach.");
        calvary.setPastor("Rev. James Thompson");
        calvary.setFacebook("https://facebook.com/calvarybaptistchicago");
        addServiceTime(calvary, "Sunday", "09:30", "Sunday Service", null);
        addServiceTime(calvary, "Sunday", "11:30", "Sunday Service", null);
        addServiceTime(calvary, "Thursday", "19:00", "Bible Study", null);
        addServiceTime(calvary, "Friday", "18:30", "Youth Group", null);
        repo.save(calvary);

        // Grace Lutheran Church
        Church grace = new Church();
        grace.setName("Grace Lutheran Church");
        grace.setDenomination("Lutheran");
        grace.setAddress("7300 W Agatite Ave");
        grace.setCity("Harwood Heights");
        grace.setState("IL");
        grace.setZipCode("60706");
        grace.setPhone("(708) 867-5309");
        grace.setEmail("office@gracelc.org");
        grace.setWebsite("https://gracelc.org");
        grace.setDescription("A welcoming ELCA Lutheran congregation rooted in scripture, sacrament, and service. All are welcome at God's table.");
        grace.setPastor("Pastor Sarah Johansson");
        grace.setYoutube("https://youtube.com/@gracelutheran");
        addServiceTime(grace, "Sunday", "08:30", "Sunday Service", null);
        addServiceTime(grace, "Sunday", "10:45", "Sunday Service", null);
        addServiceTime(grace, "Tuesday", "09:30", "Bible Study", null);
        addServiceTime(grace, "Thursday", "18:00", "Choir / Worship Practice", null);
        repo.save(grace);
    }

    private void addServiceTime(Church church, String day, String time, String type, String language) {
        ServiceTime st = new ServiceTime();
        st.setDayOfWeek(day);
        st.setTime(time);
        st.setType(type);
        st.setLanguage(language);
        st.setChurch(church);
        church.getServiceTimes().add(st);
    }
}
