package mate.jdbc.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mate.jdbc.exception.DataProcessingException;
import mate.jdbc.lib.Dao;
import mate.jdbc.model.Car;
import mate.jdbc.model.Driver;
import mate.jdbc.model.Manufacturer;
import mate.jdbc.util.ConnectionUtil;

@Dao
public class CarDaoImpl implements CarDao {
    @Override
    public Car create(Car car) {
        String create = "INSERT INTO cars(model, manufacturer_id) VALUES(?, ?);";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement createStmt
                        = connection.prepareStatement(create, Statement.RETURN_GENERATED_KEYS)) {
            createStmt.setString(1, car.getModel());
            createStmt.setLong(2, car.getManufacturer().getId());
            createStmt.executeUpdate();
            ResultSet resultSet = createStmt.getGeneratedKeys();
            if (resultSet.next()) {
                car.setId(resultSet.getObject(1, Long.class));
            }
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot insert car to DB. Car: " + car, e);
        }
        insertDrivers(car);
        return car;
    }

    @Override
    public Optional<Car> get(Long id) {
        Car car = null;
        String select = "SELECT cars.id, model, manufacturer_id, name, country "
                + "FROM cars "
                + "JOIN manufacturers "
                + "ON manufacturer_id = manufacturers.id "
                + "WHERE cars.id = ? AND cars.is_deleted = false;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement selectStmt
                        = connection.prepareStatement(select)) {
            selectStmt.setLong(1, id);
            ResultSet resultSet = selectStmt.executeQuery();
            if (resultSet.next()) {
                car = convertToCar(resultSet);
            }
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot get car by id: " + id, e);
        }
        if (car != null) {
            car.setDrivers(getDriversByCarId(id));
        }
        return Optional.ofNullable(car);
    }

    @Override
    public List<Car> getAll() {
        List<Car> cars = new ArrayList<>();
        String select = "SELECT * "
                + "FROM cars "
                + "JOIN manufacturers "
                + "ON cars.manufacturer_id = manufacturers.id "
                + "WHERE cars.is_deleted = false;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement selectStmt
                        = connection.prepareStatement(select)) {
            ResultSet resultSet = selectStmt.executeQuery();
            while (resultSet.next()) {
                Car car = convertToCar(resultSet);
                cars.add(car);
            }
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot get cars from DB", e);
        }
        cars.forEach(car -> {
            List<Driver> drivers = getDriversByCarId(car.getId());
            car.setDrivers(drivers);
        });
        return cars;
    }

    @Override
    public Car update(Car car) {
        String update = "UPDATE cars SET model = ?, manufacturer_id = ? "
                + "WHERE id = ? AND cars.is_deleted = false;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement updateStmt
                        = connection.prepareStatement(update)) {
            updateStmt.setString(1, car.getModel());
            updateStmt.setLong(2, car.getManufacturer().getId());
            updateStmt.setLong(3, car.getId());
            updateStmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot update car: " + car, e);
        }
        deleteFromCars_Drivers(car);
        insertDrivers(car);
        return car;
    }

    @Override
    public boolean delete(Long id) {
        String softDelete = "UPDATE cars SET is_deleted = true WHERE id = ?;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement deleteStatement = connection.prepareStatement(softDelete)) {
            deleteStatement.setLong(1, id);
            return deleteStatement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new DataProcessingException("Couldn't delete car by id: " + id, e);
        }
    }

    @Override
    public List<Car> getAllByDriver(Long driverId) {
        List<Long> carsIDs = new ArrayList<>();
        List<Car> cars = new ArrayList<>();
        String select = "SELECT c.id AS carID "
                + "FROM cars c "
                + "JOIN cars_drivers cd "
                + "ON c.id = cd.car_id "
                + "WHERE cd.driver_id = ? AND c.is_deleted = false;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement selectStmt
                        = connection.prepareStatement(select)) {
            selectStmt.setLong(1, driverId);
            ResultSet resultSet = selectStmt.executeQuery();
            while (resultSet.next()) {
                carsIDs.add(resultSet.getObject("carID", Long.class));
            }
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot get cars from DB", e);
        }
        carsIDs.forEach(id -> {
            Car car = get(id).get();
            cars.add(car);
        });
        return cars;
    }

    private Car convertToCar(ResultSet resultSet) throws SQLException {
        Car car = new Car();
        car.setId(resultSet.getObject("cars.id", Long.class));
        car.setModel(resultSet.getString("model"));
        Long manufacturerId = resultSet.getObject("manufacturer_id", Long.class);
        String manufacturerName = resultSet.getString("name");
        String manufacturerCountry = resultSet.getString("country");
        car.setManufacturer(new Manufacturer(manufacturerId,
                manufacturerName, manufacturerCountry));
        return car;
    }

    private List<Driver> getDriversByCarId(Long id) {
        String select = "SELECT driver_id, name, license_number "
                + "FROM cars_drivers cd "
                + "JOIN drivers d "
                + "ON cd.driver_id = d.id "
                + "WHERE cd.car_id = ? AND d.is_deleted = false;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement getDriversStmt
                        = connection.prepareStatement(select)) {
            getDriversStmt.setLong(1, id);
            List<Driver> drivers = new ArrayList<>();
            ResultSet resultSet = getDriversStmt.executeQuery();
            while (resultSet.next()) {
                Long driverId = resultSet.getObject("driver_id", Long.class);
                String name = resultSet.getString("name");
                String licenseNumber = resultSet.getString("license_number");
                drivers.add(new Driver(driverId, name, licenseNumber));
            }
            return drivers;
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot get drivers by car id: " + id, e);
        }
    }

    private void insertDrivers(Car car) {
        String insert = "INSERT INTO cars_drivers(car_id, driver_id) VALUES(?, ?);";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement insertStmt
                        = connection.prepareStatement(insert)) {
            insertStmt.setLong(1, car.getId());
            for (Driver driver: car.getDrivers()) {
                insertStmt.setLong(2, driver.getId());
                insertStmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot insert into cars_drivers table", e);
        }
    }

    private void deleteFromCars_Drivers(Car car) {
        String delete = "DELETE FROM cars_drivers WHERE car_id = ?;";
        try (Connection connection = ConnectionUtil.getConnection();
                PreparedStatement deleteStmt
                        = connection.prepareStatement(delete)) {
            deleteStmt.setLong(1, car.getId());
            deleteStmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataProcessingException("Cannot delete relation for car: " + car, e);
        }
    }
}
