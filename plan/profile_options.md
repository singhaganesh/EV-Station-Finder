# Profile Screen Options & Specifications

This document lists all options available on the **Profile** screen of the EV Station Finder app, explaining their intended use, actual functionality, and current implementation status.

---

## Profile Header (User Info Card)
Displays basic user details when signed in.
* **Displays**: User's dynamic avatar circle (with name initials), Display Name, and Email address.
* **Usage**: Identifies the logged-in user and confirms account synchronization status.
* **Status**: **Fully Implemented** (Synced with Supabase authentication metadata and Google Sign-In profile).

---

## 1. Account Section

| Option | Subtitle / Label | Purpose | Status | Technical Details |
| :--- | :--- | :--- | :--- | :--- |
| **Edit Profile** | Name, photo | Allows the user to edit their display name and profile picture. | **Placeholder** | UI row is visible but has no active click listener (`onClick = null`). |
| **Google Account** | Connected to Google Sign-In | Displays the authentication connection status. | **Placeholder** | UI row is visible but not clickable; highlights that the account is authenticated via Google. |

---

## 2. My EV Section

| Option | Subtitle / Label | Purpose | Status | Technical Details |
| :--- | :--- | :--- | :--- | :--- |
| **My Vehicles** | Manage your electric vehicles | Allows users to add and manage their electric vehicles (vehicle model, battery capacity, maximum range). | **Fully Implemented** | Navigates to the `"vehicles"` route (`VehicleProfileScreen`). Changes are synced locally and to the backend cloud database. |
| **Charging Preferences** | Connector, power, filters | Allows users to specify default search filters like preferred connector type, minimum power, and availability. | **Fully Implemented** | Navigates to the `"vehicles"` route (`VehicleProfileScreen`) where preferred filters are configured. |

---

## 3. My Activity Section

| Option | Subtitle / Label | Purpose | Status | Technical Details |
| :--- | :--- | :--- | :--- | :--- |
| **Saved Stations** | (No subtitle) | Navigates to the user's saved/favorite charging stations list. | **Fully Implemented** | Navigates to the `NavigationItem.Saved.route` (`SavedScreen`). Displays cloud-saved and local-saved favorites. |
| **My Reviews** | (No subtitle) | View and manage a list of reviews previously submitted by the user. | **Placeholder** | Click listener is not assigned (`onClick = null`). |
| **Route History** | (No subtitle) | View previous route planner entries and histories. | **Placeholder** | Click listener is not assigned (`onClick = null`). |

---

## 4. Settings Section

| Option | Subtitle / Label | Purpose | Status | Technical Details |
| :--- | :--- | :--- | :--- | :--- |
| **App Settings** | (No subtitle) | Configures general app preferences (units of distance, maps theme, etc.). | **Placeholder** | Navigates to a no-op handler in `MainActivity.kt`. |
| **Notifications** | (No subtitle) | Setup notification preferences (alerts, station availability notifications). | **Placeholder** | Click listener is not assigned (`onClick = null`). |

---

## 5. About Section

| Option | Subtitle / Label | Purpose | Status | Technical Details |
| :--- | :--- | :--- | :--- | :--- |
| **Help & Support** | (No subtitle) | Navigates to support documentation, contact forms, or FAQs. | **Placeholder** | Click listener is not assigned (`onClick = null`). |
| **Privacy Policy** | (No subtitle) | View legal privacy policy document links or screens. | **Placeholder** | Click listener is not assigned (`onClick = null`). |
| **Version** | 1.0.0 | Displays current application build version. | **Fully Implemented** | Non-clickable row displaying hardcoded version `1.0.0`. |

---

## 6. Actions

### Sign Out Button
* **Purpose**: Logs out the authenticated user.
* **Usage**: Clicking this clears the local favorites cache (`FavoriteManager.clear(context)`), calls the Supabase Auth sign-out function, clears user-specific variables in the `StationViewModel`, and redirects the UI back to the guest profile state.
* **Status**: **Fully Implemented** (Triggers `viewModel.signOut(context)`).
